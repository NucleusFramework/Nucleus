on dumpEl(el, depth)
    set out to ""
    set indent to ""
    repeat depth times
        set indent to indent & "  "
    end repeat
    set r to ""
    set d to ""
    try
        set r to role of el
    end try
    try
        set d to description of el
    end try
    if d is missing value then set d to "?"
    set out to indent & r & " [" & d & "]"
    set kids to {}
    try
        set kids to UI elements of el
    end try
    set out to out & " kids=" & (count of kids) & linefeed
    if depth < 6 then
        repeat with k in kids
            set out to out & my dumpEl(k, depth + 1)
        end repeat
    end if
    return out
end dumpEl

tell application "System Events"
    tell process "java"
        tell window 1
            return my dumpEl(it, 0)
        end tell
    end tell
end tell
