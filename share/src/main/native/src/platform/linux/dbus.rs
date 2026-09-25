//! A small D-Bus client that can only call XDG portal's OpenURI/OpenFile.

use std::{
    env,
    io::{self, Read, Write},
    os::fd::{AsRawFd, FromRawFd, RawFd},
    os::unix::net::UnixStream,
    path::{Path, PathBuf},
    time::Duration,
};

const DEST: &str = "org.freedesktop.portal.Desktop";
const PATH: &str = "/org/freedesktop/portal/desktop";
const IFACE: &str = "org.freedesktop.portal.OpenURI";
const FILECHOOSER: &str = "org.freedesktop.portal.FileChooser";

struct Msg {
    mtype: u8,
    member: Option<String>,
    path: Option<String>,
    le: bool,
    body: Vec<u8>,
}

/// D-Bus has a max 128MiB message size.
const MAX_MSG: usize = 128 * 1024 * 1024;

/// Open a local file through the portal, showing a prompt if `ask` is true.
pub(super) fn open_path(parent: &str, path: &Path, ask: bool) -> io::Result<Request> {
    let file = std::fs::File::open(path)?;
    Connection::session()?.open_file(parent, file.as_raw_fd(), ask)
}

/// Open a URI through the portal, showing a prompt if `ask` is true.
pub(super) fn open_uri(parent: &str, uri: &str, ask: bool) -> io::Result<Request> {
    Connection::session()?.open_uri(parent, uri, ask)
}

/// Show a save file dialog and returns the chosen destination paths.
///
/// The returned set will be empty if the user canceled the dialog.
pub(super) fn save_files(parent: &str, title: &str, names: &[Vec<u8>]) -> io::Result<Vec<PathBuf>> {
    Connection::session()?.save_files(parent, title, names)
}

struct Connection {
    stream: UnixStream,
    serial: u32,
}

pub(super) struct Request {
    connection: Connection,
    path: String,
}

impl Request {
    pub(super) fn wait(mut self) -> io::Result<()> {
        self.connection.await_response(&self.path)
    }
}

impl Connection {
    fn session() -> io::Result<Self> {
        let addr = env::var("DBUS_SESSION_BUS_ADDRESS")
            .map_err(|_| err("DBUS_SESSION_BUS_ADDRESS not set"))?;
        let mut stream = connect(&addr)?;
        stream.set_read_timeout(Some(Duration::from_secs(15)))?;
        authenticate(&mut stream)?;

        let mut connection = Self { stream, serial: 0 };
        connection.call(
            "org.freedesktop.DBus",
            "/org/freedesktop/DBus",
            "org.freedesktop.DBus",
            "Hello",
            "",
            &[],
            0,
        )?;
        let mut rule = Vec::new();
        put_string(
            &mut rule,
            "type='signal',interface='org.freedesktop.portal.Request',member='Response'",
        );
        let _ = connection.call(
            "org.freedesktop.DBus",
            "/org/freedesktop/DBus",
            "org.freedesktop.DBus",
            "AddMatch",
            "s",
            &rule,
            0,
        );
        Ok(connection)
    }

    fn open_file(mut self, parent: &str, fd: RawFd, ask: bool) -> io::Result<Request> {
        let mut body = Vec::new();
        put_string(&mut body, parent); // parent window
        put_u32(&mut body, 0); // fd index
        put_options(&mut body, ask);
        let serial = self.next_serial();
        let msg = method_call(serial, DEST, PATH, IFACE, "OpenFile", "sha{sv}", &body, 1);
        send_with_fd(&self.stream, &msg, fd)?;
        let path = self.read_request_handle()?;
        Ok(Request { connection: self, path })
    }

    fn open_uri(mut self, parent: &str, uri: &str, ask: bool) -> io::Result<Request> {
        let mut body = Vec::new();
        put_string(&mut body, parent); // parent window
        put_string(&mut body, uri);
        put_options(&mut body, ask);
        let serial = self.next_serial();
        let msg = method_call(serial, DEST, PATH, IFACE, "OpenURI", "ssa{sv}", &body, 0);
        self.stream.write_all(&msg)?;
        let path = self.read_request_handle()?;
        Ok(Request { connection: self, path })
    }

    fn save_files(mut self, parent: &str, title: &str, names: &[Vec<u8>]) -> io::Result<Vec<PathBuf>> {
        let mut body = Vec::new();
        put_string(&mut body, parent); // parent window
        put_string(&mut body, title);
        put_savefiles_options(&mut body, names);
        let serial = self.next_serial();
        let msg = method_call(serial, DEST, PATH, FILECHOOSER, "SaveFiles", "ssa{sv}", &body, 0);
        self.stream.write_all(&msg)?;
        let request = self.read_request_handle()?;

        // the dialog stays up only while this connection is open,
        // so we just pick a randomly long timeout value (5 mins).
        self.stream.set_read_timeout(Some(Duration::from_secs(300)))?;
        loop {
            let m = self.read_message()?;
            if m.mtype == 4
                && m.path.as_deref() == Some(request.as_str())
                && m.member.as_deref() == Some("Response")
            {
                return Ok(parse_save_response(m.le, &m.body));
            }
        }
    }

    fn next_serial(&mut self) -> u32 {
        self.serial += 1;
        self.serial
    }

    /// Send a method call and read until its reply arrives.
    #[allow(clippy::too_many_arguments)]
    fn call(&mut self, dest: &str, path: &str, iface: &str, member: &str, sig: &str,
        body: &[u8], fds: u32) -> io::Result<()> {
        let serial = self.next_serial();
        let msg = method_call(serial, dest, path, iface, member, sig, body, fds);
        self.stream.write_all(&msg)?;
        loop {
            match self.read_message()?.mtype {
                2 => return Ok(()),            // method return
                3 => return Err(err("D-Bus error reply")),
                _ => continue,
            }
        }
    }

    fn read_message(&mut self) -> io::Result<Msg> {
        let mut head = [0u8; 16];
        self.stream.read_exact(&mut head)?;
        let le = head[0] == b'l';
        let body_len = read_u32(le, &head[4..8]) as usize;
        let fields_len = read_u32(le, &head[12..16]) as usize;
        if body_len > MAX_MSG || fields_len > MAX_MSG {
            return Err(err("oversized D-Bus message"));
        }

        let mut fields = vec![0u8; fields_len];
        self.stream.read_exact(&mut fields)?;
        let pad = (8 - ((16 + fields_len) % 8)) % 8;
        io::copy(&mut (&self.stream).take(pad as u64), &mut io::sink())?;
        let mut body = vec![0u8; body_len];
        self.stream.read_exact(&mut body)?;

        Ok(Msg {
            mtype: head[1],
            member: header_field(le, &fields, 3), // MEMBER
            path: header_field(le, &fields, 1),   // PATH
            le,
            body,
        })
    }

    fn read_request_handle(&mut self) -> io::Result<String> {
        loop {
            let m = self.read_message()?;
            match m.mtype {
                2 => {
                    return Reader { b: &m.body, pos: 0, le: m.le }
                        .string()
                        .ok_or_else(|| err("missing request handle"))
                }
                3 => return Err(err("portal returned an error")),
                _ => continue,
            }
        }
    }

    /// Wait for the portal's Response signal to our request.
    fn await_response(&mut self, request: &str) -> io::Result<()> {
        // same as above, basically a 5-min timeout
        self.stream.set_read_timeout(Some(Duration::from_secs(300)))?;
        loop {
            match self.read_message() {
                Ok(m)
                    if m.mtype == 4
                        && m.path.as_deref() == Some(request)
                        && m.member.as_deref() == Some("Response") =>
                {
                    return Ok(())
                }
                Ok(_) => continue,
                Err(_) => return Ok(()),
            }
        }
    }
}

fn align(buf: &mut Vec<u8>, n: usize) {
    let rem = buf.len() % n;
    if rem != 0 {
        buf.resize(buf.len() + n - rem, 0);
    }
}

fn put_u32(buf: &mut Vec<u8>, v: u32) {
    align(buf, 4);
    buf.extend_from_slice(&v.to_le_bytes());
}

fn put_string(buf: &mut Vec<u8>, s: &str) {
    put_u32(buf, s.len() as u32);
    buf.extend_from_slice(s.as_bytes());
    buf.push(0);
}

fn put_signature(buf: &mut Vec<u8>, s: &str) {
    buf.push(s.len() as u8);
    buf.extend_from_slice(s.as_bytes());
    buf.push(0);
}

/// Append an `a{sv}` options dict — `{'ask': <true>}` or empty.
fn put_options(buf: &mut Vec<u8>, ask: bool) {
    align(buf, 4);
    let len_pos = buf.len();
    buf.extend_from_slice(&0u32.to_le_bytes());
    align(buf, 8);
    let start = buf.len();
    if ask {
        put_string(buf, "ask");
        put_signature(buf, "b");
        put_u32(buf, 1);
    }
    let len = (buf.len() - start) as u32;
    buf[len_pos..len_pos + 4].copy_from_slice(&len.to_le_bytes());
}

/// Append an `a{sv}` with a single `files` (aay) entry of suggested names.
fn put_savefiles_options(buf: &mut Vec<u8>, names: &[Vec<u8>]) {
    align(buf, 4);
    let len_pos = buf.len();
    buf.extend_from_slice(&0u32.to_le_bytes());
    align(buf, 8);
    let start = buf.len();
    put_string(buf, "files");
    put_signature(buf, "aay");
    put_aay(buf, names);
    let len = (buf.len() - start) as u32;
    buf[len_pos..len_pos + 4].copy_from_slice(&len.to_le_bytes());
}

/// Append an `aay` (array of byte arrays).
fn put_aay(buf: &mut Vec<u8>, items: &[Vec<u8>]) {
    align(buf, 4);
    let len_pos = buf.len();
    buf.extend_from_slice(&0u32.to_le_bytes());
    let start = buf.len();
    for item in items {
        put_u32(buf, item.len() as u32);
        buf.extend_from_slice(item);
    }
    let len = (buf.len() - start) as u32;
    buf[len_pos..len_pos + 4].copy_from_slice(&len.to_le_bytes());
}

fn put_header_str(buf: &mut Vec<u8>, code: u8, sig: &str, val: &str) {
    align(buf, 8);
    buf.push(code);
    put_signature(buf, sig);
    put_string(buf, val);
}

#[allow(clippy::too_many_arguments)]
fn method_call(serial: u32, dest: &str, path: &str, iface: &str, member: &str, sig: &str,
    body: &[u8], fds: u32) -> Vec<u8> {
    let mut fields = Vec::new();
    put_header_str(&mut fields, 1, "o", path); // PATH
    put_header_str(&mut fields, 6, "s", dest); // DESTINATION
    put_header_str(&mut fields, 2, "s", iface); // INTERFACE
    put_header_str(&mut fields, 3, "s", member); // MEMBER
    if !sig.is_empty() {
        align(&mut fields, 8);
        fields.push(8); // SIGNATURE
        put_signature(&mut fields, "g");
        put_signature(&mut fields, sig);
    }
    if fds > 0 {
        align(&mut fields, 8);
        fields.push(9); // UNIX_FDS
        put_signature(&mut fields, "u");
        put_u32(&mut fields, fds);
    }

    let mut msg = Vec::with_capacity(16 + fields.len() + body.len());
    msg.extend_from_slice(&[b'l', 1, 0, 1]); // little-endian, method call, no flags, v1
    msg.extend_from_slice(&(body.len() as u32).to_le_bytes());
    msg.extend_from_slice(&serial.to_le_bytes());
    msg.extend_from_slice(&(fields.len() as u32).to_le_bytes());
    msg.extend_from_slice(&fields);
    align(&mut msg, 8);
    msg.extend_from_slice(body);
    msg
}

// ---- parsing (just enough to find header fields we care about) ----

fn read_u32(le: bool, b: &[u8]) -> u32 {
    let a = [b[0], b[1], b[2], b[3]];
    if le {
        u32::from_le_bytes(a)
    } else {
        u32::from_be_bytes(a)
    }
}

/// Extract the string value of a header field by code (e.g. MEMBER=3, PATH=1).
fn header_field(le: bool, fields: &[u8], want: u8) -> Option<String> {
    let mut pos = 0usize;
    let align = |p: usize, n: usize| (p + n - 1) & !(n - 1);
    while pos < fields.len() {
        pos = align(pos, 8);
        let code = *fields.get(pos)?;
        pos += 1;
        let sig_len = *fields.get(pos)? as usize;
        pos += 1;
        let sig = fields.get(pos..pos + sig_len)?;
        pos += sig_len + 1; // signature bytes + nul
        match sig {
            b"s" | b"o" => {
                pos = align(pos, 4);
                let len = read_u32(le, fields.get(pos..pos + 4)?) as usize;
                pos += 4;
                let val = fields.get(pos..pos + len)?;
                pos += len + 1;
                if code == want {
                    return Some(String::from_utf8_lossy(val).into_owned());
                }
            }
            b"g" => {
                let len = *fields.get(pos)? as usize;
                pos += 1 + len + 1;
            }
            b"u" => {
                pos = align(pos, 4) + 4;
            }
            _ => return None,
        }
    }
    None
}

fn parse_save_response(le: bool, body: &[u8]) -> Vec<PathBuf> {
    let mut r = Reader { b: body, pos: 0, le };
    if r.u32() != Some(0) {
        return Vec::new(); // cancelled or error
    }
    let uris = r.uris();
    let mut paths = Vec::with_capacity(uris.len());
    for uri in &uris {
        match super::file_uri_to_path(uri) {
            Some(path) => paths.push(path),
            None => return Vec::new(), // keep the result aligned 1:1 with our items
        }
    }
    paths
}

fn elem_align(c: u8) -> usize {
    match c {
        b'y' | b'g' | b'v' => 1,
        b'n' | b'q' => 2,
        b'b' | b'u' | b'i' | b'h' | b's' | b'o' | b'a' => 4,
        _ => 8,
    }
}

fn skip_type(sig: &[u8]) -> Option<&[u8]> {
    let (c, rest) = sig.split_first()?;
    match c {
        b'a' => skip_type(rest),
        b'(' | b'{' => {
            let mut depth = 1;
            let mut r = rest;
            while depth > 0 {
                let (x, after) = r.split_first()?;
                match x {
                    b'(' | b'{' => depth += 1,
                    b')' | b'}' => depth -= 1,
                    _ => {}
                }
                r = after;
            }
            Some(r)
        }
        _ => Some(rest),
    }
}

struct Reader<'a> {
    b: &'a [u8],
    pos: usize,
    le: bool,
}

impl Reader<'_> {
    fn align(&mut self, n: usize) {
        self.pos = (self.pos + n - 1) & !(n - 1);
    }

    fn u32(&mut self) -> Option<u32> {
        self.align(4);
        let s = self.b.get(self.pos..self.pos + 4)?;
        self.pos += 4;
        Some(read_u32(self.le, s))
    }

    fn string(&mut self) -> Option<String> {
        let n = self.u32()? as usize;
        let s = self.b.get(self.pos..self.pos + n)?;
        self.pos += n + 1; // bytes + nul
        Some(String::from_utf8_lossy(s).into_owned())
    }

    fn signature(&mut self) -> Option<String> {
        let n = *self.b.get(self.pos)? as usize;
        self.pos += 1;
        let s = self.b.get(self.pos..self.pos + n)?;
        self.pos += n + 1;
        Some(String::from_utf8_lossy(s).into_owned())
    }

    fn string_array(&mut self) -> Option<Vec<String>> {
        let n = self.u32()? as usize;
        let end = self.pos + n;
        let mut v = Vec::new();
        while self.pos < end {
            v.push(self.string()?);
        }
        Some(v)
    }

    fn uris(&mut self) -> Vec<String> {
        let Some(n) = self.u32() else {
            return Vec::new();
        };
        self.align(8);
        let end = self.pos + n as usize;
        while self.pos < end {
            self.align(8);
            let (Some(key), Some(sig)) = (self.string(), self.signature()) else {
                break;
            };
            if key == "uris" && sig == "as" {
                return self.string_array().unwrap_or_default();
            }
            if self.skip_value(sig.as_bytes()).is_none() {
                break;
            }
        }
        Vec::new()
    }

    fn skip_value<'s>(&mut self, sig: &'s [u8]) -> Option<&'s [u8]> {
        let (c, rest) = sig.split_first()?;
        match c {
            b'y' => self.pos += 1,
            b'b' | b'u' | b'i' | b'h' => {
                self.align(4);
                self.pos += 4;
            }
            b'n' | b'q' => {
                self.align(2);
                self.pos += 2;
            }
            b'x' | b't' | b'd' => {
                self.align(8);
                self.pos += 8;
            }
            b's' | b'o' => {
                self.string()?;
            }
            b'g' => {
                self.signature()?;
            }
            b'v' => {
                let inner = self.signature()?;
                self.skip_value(inner.as_bytes())?;
            }
            b'a' => {
                let len = self.u32()? as usize;
                self.align(elem_align(*rest.first()?));
                let end = self.pos + len;
                while self.pos < end {
                    self.skip_value(rest)?;
                }
                self.pos = end;
                return skip_type(rest);
            }
            b'(' | b'{' => {
                self.align(8);
                let mut inner = rest;
                while *inner.first()? != b')' && *inner.first()? != b'}' {
                    inner = self.skip_value(inner)?;
                }
                return Some(&inner[1..]);
            }
            _ => return None,
        }
        Some(rest)
    }
}


fn connect(addr: &str) -> io::Result<UnixStream> {
    for entry in addr.split(';') {
        let Some(rest) = entry.strip_prefix("unix:") else {
            continue;
        };
        for kv in rest.split(',') {
            if let Some(p) = kv.strip_prefix("path=") {
                return UnixStream::connect(p);
            }
            if let Some(a) = kv.strip_prefix("abstract=") {
                return connect_abstract(a.as_bytes());
            }
        }
    }
    Err(err("no unix D-Bus address"))
}

fn connect_abstract(name: &[u8]) -> io::Result<UnixStream> {
    unsafe {
        let mut addr: libc::sockaddr_un = std::mem::zeroed();
        if name.len() >= addr.sun_path.len() {
            return Err(err("abstract socket name too long"));
        }
        let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0);
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        addr.sun_family = libc::AF_UNIX as _;
        for (i, &b) in name.iter().enumerate() {
            addr.sun_path[i + 1] = b as _; // leading nul marks an abstract socket
        }
        let len = std::mem::size_of::<libc::sa_family_t>() + 1 + name.len();
        if libc::connect(fd, &addr as *const _ as *const libc::sockaddr, len as _) < 0 {
            let e = io::Error::last_os_error();
            libc::close(fd);
            return Err(e);
        }
        Ok(UnixStream::from_raw_fd(fd))
    }
}

fn authenticate(stream: &mut UnixStream) -> io::Result<()> {
    stream.write_all(&[0])?; // required null byte
    let uid = unsafe { libc::getuid() };
    stream.write_all(format!("AUTH EXTERNAL {}\r\n", hex(&uid.to_string())).as_bytes())?;
    if !read_line(stream)?.starts_with("OK") {
        return Err(err("D-Bus auth rejected"));
    }
    stream.write_all(b"NEGOTIATE_UNIX_FD\r\n")?;
    if !read_line(stream)?.starts_with("AGREE_UNIX_FD") {
        return Err(err("D-Bus peer refused fd passing"));
    }
    stream.write_all(b"BEGIN\r\n")?;
    Ok(())
}

fn read_line(stream: &mut UnixStream) -> io::Result<String> {
    let mut out = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        stream.read_exact(&mut byte)?;
        match byte[0] {
            b'\n' => break,
            b'\r' => {}
            b => out.push(b),
        }
    }
    Ok(String::from_utf8_lossy(&out).into_owned())
}

/// Send `data` with `fd` attached as SCM_RIGHTS ancillary data.
fn send_with_fd(stream: &UnixStream, data: &[u8], fd: RawFd) -> io::Result<()> {
    unsafe {
        let mut iov = libc::iovec {
            iov_base: data.as_ptr() as *mut libc::c_void,
            iov_len: data.len(),
        };
        let mut cbuf = [0u64; 8]; // 8-byte-aligned scratch for one fd's control msg
        let mut msg: libc::msghdr = std::mem::zeroed();
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        msg.msg_control = cbuf.as_mut_ptr() as *mut libc::c_void;
        msg.msg_controllen = std::mem::size_of_val(&cbuf) as _;

        let cmsg = libc::CMSG_FIRSTHDR(&msg);
        (*cmsg).cmsg_level = libc::SOL_SOCKET;
        (*cmsg).cmsg_type = libc::SCM_RIGHTS;
        (*cmsg).cmsg_len = libc::CMSG_LEN(std::mem::size_of::<RawFd>() as u32) as _;
        std::ptr::copy_nonoverlapping(
            &fd as *const RawFd as *const u8,
            libc::CMSG_DATA(cmsg),
            std::mem::size_of::<RawFd>(),
        );
        msg.msg_controllen = libc::CMSG_SPACE(std::mem::size_of::<RawFd>() as u32) as _;

        let n = libc::sendmsg(stream.as_raw_fd(), &msg, 0);
        if n < 0 {
            return Err(io::Error::last_os_error());
        }
        if n as usize != data.len() {
            return Err(err("short D-Bus sendmsg"));
        }
    }
    Ok(())
}

fn hex(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 2);
    for b in s.bytes() {
        out.push(char::from_digit((b >> 4) as u32, 16).unwrap());
        out.push(char::from_digit((b & 0xf) as u32, 16).unwrap());
    }
    out
}

fn err(msg: &str) -> io::Error {
    io::Error::other(msg)
}
