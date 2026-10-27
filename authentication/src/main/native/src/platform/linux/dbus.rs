//! A small system-bus D-Bus client that can only ask polkit's authority to check, and
//! cancel the check of, an action for this process.

use std::{
    env,
    io::{self, Read, Write},
    os::fd::FromRawFd,
    os::unix::net::UnixStream,
    sync::{
        atomic::{AtomicU32, Ordering},
        Arc, Mutex,
    },
    time::Duration,
};

const POLKIT: &str = "org.freedesktop.PolicyKit1";
const AUTHORITY_PATH: &str = "/org/freedesktop/PolicyKit1/Authority";
const AUTHORITY: &str = "org.freedesktop.PolicyKit1.Authority";
const DEFAULT_SYSTEM_BUS: &str = "unix:path=/var/run/dbus/system_bus_socket";

/// `CheckAuthorizationFlags::AllowUserInteraction`.
const ALLOW_USER_INTERACTION: u32 = 1;
/// D-Bus header flag: the caller does not read the reply.
const NO_REPLY_EXPECTED: u8 = 1;

/// D-Bus has a max 128MiB message size.
const MAX_MSG: usize = 128 * 1024 * 1024;

/// What `CheckAuthorization` answered.
pub(super) struct AuthorizationResult {
    pub(super) is_authorized: bool,
    pub(super) is_challenge: bool,
    pub(super) dismissed: bool,
}

/// A D-Bus error reply: its name (`org.freedesktop.PolicyKit1.Error.Cancelled`…) and message.
pub(super) struct ErrorReply {
    pub(super) name: String,
    pub(super) message: String,
}

pub(super) enum CallError {
    Io(io::Error),
    Reply(ErrorReply),
}

impl From<io::Error> for CallError {
    fn from(value: io::Error) -> Self {
        CallError::Io(value)
    }
}

/// Sends on a connection another thread is blocked reading: the cancel path.
#[derive(Clone)]
pub(super) struct Sender {
    stream: Arc<Mutex<UnixStream>>,
    serial: Arc<AtomicU32>,
}

impl Sender {
    /// Asks polkit to abandon the check started with `cancellation_id`; it then answers
    /// that check with `org.freedesktop.PolicyKit1.Error.Cancelled`. Only the connection
    /// that started a check may cancel it, hence this shared writer.
    pub(super) fn cancel(&self, cancellation_id: &str) -> io::Result<()> {
        let mut body = Vec::new();
        put_string(&mut body, cancellation_id);
        let serial = self.serial.fetch_add(1, Ordering::Relaxed) + 1;
        let msg = method_call(
            serial,
            NO_REPLY_EXPECTED,
            POLKIT,
            AUTHORITY_PATH,
            AUTHORITY,
            "CancelCheckAuthorization",
            "s",
            &body,
        );
        self.write(&msg)
    }

    fn write(&self, msg: &[u8]) -> io::Result<()> {
        let mut stream = self.stream.lock().map_err(|_| err("poisoned D-Bus writer"))?;
        stream.write_all(msg)
    }

    fn next_serial(&self) -> u32 {
        self.serial.fetch_add(1, Ordering::Relaxed) + 1
    }
}

pub(super) struct Connection {
    reader: UnixStream,
    sender: Sender,
}

struct Msg {
    mtype: u8,
    reply_serial: Option<u32>,
    error_name: Option<String>,
    le: bool,
    body: Vec<u8>,
}

impl Connection {
    pub(super) fn system() -> io::Result<Self> {
        let addr = env::var("DBUS_SYSTEM_BUS_ADDRESS").unwrap_or_else(|_| DEFAULT_SYSTEM_BUS.to_owned());
        let mut stream = connect(&addr)?;
        stream.set_read_timeout(Some(Duration::from_secs(15)))?;
        authenticate(&mut stream)?;
        let writer = stream.try_clone()?;
        let mut connection = Self {
            reader: stream,
            sender: Sender {
                stream: Arc::new(Mutex::new(writer)),
                serial: Arc::new(AtomicU32::new(0)),
            },
        };
        connection
            .call(
                "org.freedesktop.DBus",
                "/org/freedesktop/DBus",
                "org.freedesktop.DBus",
                "Hello",
                "",
                &[],
            )
            .map_err(CallError::into_io)?;
        Ok(connection)
    }

    pub(super) fn sender(&self) -> Sender {
        self.sender.clone()
    }

    /// Whether polkit's authority answers at all (it is bus-activated, so this starts it).
    pub(super) fn ping_polkit(&mut self) -> Result<(), CallError> {
        self.call(POLKIT, AUTHORITY_PATH, "org.freedesktop.DBus.Peer", "Ping", "", &[])
            .map(|_| ())
    }

    /// Sends `CheckAuthorization` for this process, letting polkit's agent prompt the user,
    /// and returns the serial to [Self::finish_check] on. Split from the wait so the caller
    /// can order the send against a concurrent [Sender::cancel].
    pub(super) fn start_check(&mut self, action_id: &str, cancellation_id: &str) -> io::Result<u32> {
        let mut body = Vec::new();
        put_unix_process_subject(&mut body)?;
        put_string(&mut body, action_id);
        put_empty_dict(&mut body); // details a{ss}: a non-empty one needs privileges
        put_u32(&mut body, ALLOW_USER_INTERACTION);
        put_string(&mut body, cancellation_id);
        self.send(
            POLKIT,
            AUTHORITY_PATH,
            AUTHORITY,
            "CheckAuthorization",
            "(sa{sv})sa{ss}us",
            &body,
        )
    }

    /// Waits for the reply to [Self::start_check]. Blocks without deadline: the agent's
    /// dialog stays up as long as the user wants.
    pub(super) fn finish_check(&mut self, serial: u32) -> Result<AuthorizationResult, CallError> {
        self.reader.set_read_timeout(None)?;
        let reply = self.reply(serial)?;
        parse_authorization_result(reply.le, &reply.body)
            .ok_or_else(|| err("malformed CheckAuthorization reply").into())
    }

    /// Sends a method call and reads until its reply (or error) arrives.
    fn call(
        &mut self,
        dest: &str,
        path: &str,
        iface: &str,
        member: &str,
        sig: &str,
        body: &[u8],
    ) -> Result<Msg, CallError> {
        let serial = self.send(dest, path, iface, member, sig, body)?;
        self.reply(serial)
    }

    fn send(&self, dest: &str, path: &str, iface: &str, member: &str, sig: &str, body: &[u8]) -> io::Result<u32> {
        let serial = self.sender.next_serial();
        let msg = method_call(serial, 0, dest, path, iface, member, sig, body);
        self.sender.write(&msg)?;
        Ok(serial)
    }

    /// Reads until the reply (or error) to `serial` arrives.
    fn reply(&mut self, serial: u32) -> Result<Msg, CallError> {
        loop {
            let m = self.read_message()?;
            if m.reply_serial != Some(serial) {
                continue; // signals, or the reply to a fire-and-forget cancel
            }
            match m.mtype {
                2 => return Ok(m), // method return
                3 => {
                    let message = Reader {
                        b: &m.body,
                        pos: 0,
                        le: m.le,
                    }
                    .string()
                    .unwrap_or_default();
                    return Err(CallError::Reply(ErrorReply {
                        name: m.error_name.unwrap_or_default(),
                        message,
                    }));
                }
                _ => continue,
            }
        }
    }

    fn read_message(&mut self) -> io::Result<Msg> {
        let mut head = [0u8; 16];
        self.reader.read_exact(&mut head)?;
        let le = head[0] == b'l';
        let body_len = read_u32(le, &head[4..8]) as usize;
        let fields_len = read_u32(le, &head[12..16]) as usize;
        if body_len > MAX_MSG || fields_len > MAX_MSG {
            return Err(err("oversized D-Bus message"));
        }

        let mut fields = vec![0u8; fields_len];
        self.reader.read_exact(&mut fields)?;
        let pad = (8 - ((16 + fields_len) % 8)) % 8;
        io::copy(&mut (&self.reader).take(pad as u64), &mut io::sink())?;
        let mut body = vec![0u8; body_len];
        self.reader.read_exact(&mut body)?;

        let header = parse_header(le, &fields);
        Ok(Msg {
            mtype: head[1],
            reply_serial: header.reply_serial,
            error_name: header.error_name,
            le,
            body,
        })
    }
}

impl CallError {
    fn into_io(self) -> io::Error {
        match self {
            CallError::Io(error) => error,
            CallError::Reply(reply) => err(&format!("{}: {}", reply.name, reply.message)),
        }
    }
}

// ---- marshalling ----

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

fn put_u64(buf: &mut Vec<u8>, v: u64) {
    align(buf, 8);
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

/// Appends an empty dictionary (`a{..}`): length 0, padded to the 8-byte entry alignment.
fn put_empty_dict(buf: &mut Vec<u8>) {
    put_u32(buf, 0);
    align(buf, 8);
}

/// Appends the `(sa{sv})` subject `('unix-process', {'pid', 'start-time', 'uid'})`. The
/// start time rules out a recycled pid, as polkit's own clients do.
fn put_unix_process_subject(buf: &mut Vec<u8>) -> io::Result<()> {
    let pid = std::process::id();
    let start_time = process_start_time()?;
    let uid = unsafe { libc::getuid() } as i32;

    align(buf, 8);
    put_string(buf, "unix-process");
    align(buf, 4);
    let len_pos = buf.len();
    buf.extend_from_slice(&0u32.to_le_bytes());
    align(buf, 8);
    let start = buf.len();

    align(buf, 8);
    put_string(buf, "pid");
    put_signature(buf, "u");
    put_u32(buf, pid);

    align(buf, 8);
    put_string(buf, "start-time");
    put_signature(buf, "t");
    put_u64(buf, start_time);

    align(buf, 8);
    put_string(buf, "uid");
    put_signature(buf, "i");
    put_u32(buf, uid as u32);

    let len = (buf.len() - start) as u32;
    buf[len_pos..len_pos + 4].copy_from_slice(&len.to_le_bytes());
    Ok(())
}

/// Field 22 of `/proc/self/stat`: the start time in clock ticks since boot.
fn process_start_time() -> io::Result<u64> {
    let stat = std::fs::read_to_string("/proc/self/stat")?;
    // The command name (field 2) may hold spaces and parentheses: split after the last ')'.
    let after_comm = stat
        .rsplit_once(')')
        .map(|(_, rest)| rest)
        .ok_or_else(|| err("malformed /proc/self/stat"))?;
    // `after_comm` starts at field 3, so field 22 is its 20th entry.
    after_comm
        .split_whitespace()
        .nth(19)
        .and_then(|field| field.parse().ok())
        .ok_or_else(|| err("malformed /proc/self/stat"))
}

fn put_header_str(buf: &mut Vec<u8>, code: u8, sig: &str, val: &str) {
    align(buf, 8);
    buf.push(code);
    put_signature(buf, sig);
    put_string(buf, val);
}

#[allow(clippy::too_many_arguments)]
fn method_call(
    serial: u32,
    flags: u8,
    dest: &str,
    path: &str,
    iface: &str,
    member: &str,
    sig: &str,
    body: &[u8],
) -> Vec<u8> {
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

    let mut msg = Vec::with_capacity(16 + fields.len() + body.len());
    msg.extend_from_slice(&[b'l', 1, flags, 1]); // little-endian, method call, flags, v1
    msg.extend_from_slice(&(body.len() as u32).to_le_bytes());
    msg.extend_from_slice(&serial.to_le_bytes());
    msg.extend_from_slice(&(fields.len() as u32).to_le_bytes());
    msg.extend_from_slice(&fields);
    align(&mut msg, 8);
    msg.extend_from_slice(body);
    msg
}

// ---- parsing ----

fn read_u32(le: bool, b: &[u8]) -> u32 {
    let a = [b[0], b[1], b[2], b[3]];
    if le {
        u32::from_le_bytes(a)
    } else {
        u32::from_be_bytes(a)
    }
}

#[derive(Default)]
struct Header {
    reply_serial: Option<u32>,
    error_name: Option<String>,
}

/// Reads the header fields this client needs: REPLY_SERIAL (5) and ERROR_NAME (4).
fn parse_header(le: bool, fields: &[u8]) -> Header {
    let mut header = Header::default();
    let mut r = Reader { b: fields, pos: 0, le };
    while r.pos < fields.len() {
        r.align(8);
        let Some(&code) = fields.get(r.pos) else {
            break;
        };
        r.pos += 1;
        let Some(sig) = r.signature() else {
            break;
        };
        match (code, sig.as_str()) {
            (4, "s") => header.error_name = r.string(),
            (5, "u") => header.reply_serial = r.u32(),
            _ => {
                if r.skip_value(sig.as_bytes()).is_none() {
                    break;
                }
            }
        }
    }
    header
}

/// Parses the `(bba{ss})` reply of `CheckAuthorization`.
fn parse_authorization_result(le: bool, body: &[u8]) -> Option<AuthorizationResult> {
    let mut r = Reader { b: body, pos: 0, le };
    let is_authorized = r.u32()? != 0;
    let is_challenge = r.u32()? != 0;
    let len = r.u32()? as usize;
    r.align(8);
    let end = r.pos + len;
    let mut dismissed = false;
    while r.pos < end {
        r.align(8);
        let key = r.string()?;
        let value = r.string()?;
        if key == "polkit.dismissed" && !value.is_empty() {
            dismissed = true;
        }
    }
    Some(AuthorizationResult {
        is_authorized,
        is_challenge,
        dismissed,
    })
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_authorization_result_with_details() {
        let mut body = Vec::new();
        put_u32(&mut body, 0); // is_authorized
        put_u32(&mut body, 0); // is_challenge
        align(&mut body, 4);
        let len_pos = body.len();
        body.extend_from_slice(&0u32.to_le_bytes());
        align(&mut body, 8);
        let start = body.len();
        align(&mut body, 8);
        put_string(&mut body, "polkit.dismissed");
        put_string(&mut body, "true");
        let len = (body.len() - start) as u32;
        body[len_pos..len_pos + 4].copy_from_slice(&len.to_le_bytes());

        let result = parse_authorization_result(true, &body).unwrap();
        assert!(!result.is_authorized);
        assert!(!result.is_challenge);
        assert!(result.dismissed);
    }

    #[test]
    fn subject_dict_length_excludes_leading_padding() {
        let mut body = Vec::new();
        put_unix_process_subject(&mut body).unwrap();
        // "unix-process": 4 (len) + 12 + 1 nul = 17 → dict length at offset 20, entries at 24.
        let dict_len = u32::from_le_bytes(body[20..24].try_into().unwrap()) as usize;
        assert_eq!(24 + dict_len, body.len());
    }

    #[test]
    fn reads_reply_serial_and_error_name() {
        let mut fields = Vec::new();
        put_header_str(&mut fields, 4, "s", "org.freedesktop.PolicyKit1.Error.Cancelled");
        align(&mut fields, 8);
        fields.push(5);
        put_signature(&mut fields, "u");
        put_u32(&mut fields, 7);
        let header = parse_header(true, &fields);
        assert_eq!(header.reply_serial, Some(7));
        assert_eq!(
            header.error_name.as_deref(),
            Some("org.freedesktop.PolicyKit1.Error.Cancelled")
        );
    }
}
