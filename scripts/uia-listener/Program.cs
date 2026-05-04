using System;
using System.Runtime.InteropServices;
using System.Threading;
using Interop.UIAutomationClient;

namespace UiaListenerApp {

class PropHandler : IUIAutomationPropertyChangedEventHandler {
    public void HandlePropertyChangedEvent(IUIAutomationElement sender, int propertyId, object newValue) {
        string name = "";
        try { name = sender.CurrentName ?? ""; } catch {}
        Console.WriteLine($"[Prop] {name} propId={propertyId} new={newValue}");
    }
}
class FocusHandler : IUIAutomationFocusChangedEventHandler {
    public void HandleFocusChangedEvent(IUIAutomationElement sender) {
        string name = "";
        try { name = sender.CurrentName ?? ""; } catch {}
        Console.WriteLine($"[Focus] -> {name}");
    }
}
class StructHandler : IUIAutomationStructureChangedEventHandler {
    int count = 0;
    public void HandleStructureChangedEvent(IUIAutomationElement sender, StructureChangeType changeType, int[] runtimeId) {
        count++;
        if (count <= 3) Console.WriteLine($"[Struct] type={changeType}");
    }
}

class Program {
    static int Main(string[] args) {
        if (Thread.CurrentThread.GetApartmentState() != ApartmentState.MTA) {
            int rc = 0;
            var t = new Thread(() => { rc = Dispatch(args); });
            t.SetApartmentState(ApartmentState.MTA);
            t.Start();
            t.Join();
            return rc;
        }
        return Dispatch(args);
    }
    static int Dispatch(string[] args) {
        if (args.Length > 0 && args[0] == "query") return RunQuery(args);
        return Run(args);
    }
    // query <window-title-substring> <automationId> <propId>[,propId,...]
    // Prints one line per propId: "propId=N value=[...] type=T".
    // Uses Interop.UIAutomationClient so it sees UIA props >= 30070 that
    // PowerShell's old System.Windows.Automation cannot resolve.
    static int RunQuery(string[] args) {
        if (args.Length < 4) {
            Console.WriteLine("usage: UiaListener query <title> <autoId> <propId[,propId...]>");
            return 2;
        }
        string title = args[1];
        string autoId = args[2];
        var propIds = Array.ConvertAll(args[3].Split(','), s => int.Parse(s.Trim()));

        var uia = new CUIAutomation();
        var walker = uia.RawViewWalker;
        var root = uia.GetRootElement();
        IUIAutomationElement target = null;
        var child = walker.GetFirstChildElement(root);
        while (child != null) {
            try {
                string n = child.CurrentName ?? "";
                if (n.ToLower().Contains(title.ToLower())) { target = child; break; }
            } catch {}
            child = walker.GetNextSiblingElement(child);
        }
        if (target == null) { Console.WriteLine($"Window '{title}' not found"); return 1; }

        var cond = uia.CreatePropertyCondition(30011 /* AutomationIdPropertyId */, autoId);
        var el = target.FindFirst(TreeScope.TreeScope_Subtree, cond);
        if (el == null) { Console.WriteLine($"AutomationId '{autoId}' not found"); return 1; }

        Console.WriteLine($"Element: {el.CurrentName} (autoId={el.CurrentAutomationId})");
        foreach (var pid in propIds) {
            object v = null;
            string err = null;
            try { v = el.GetCurrentPropertyValue(pid); }
            catch (Exception e) { err = e.Message; }
            string tn = v == null ? "null" : v.GetType().Name;
            string repr = v == null ? "<null>" : v.ToString();
            if (err != null) Console.WriteLine($"propId={pid}  EXC: {err}");
            else Console.WriteLine($"propId={pid}  value=[{repr}]  type={tn}");
        }
        return 0;
    }
    static int Run(string[] args) {
        Console.WriteLine($"Apartment: {Thread.CurrentThread.GetApartmentState()}");
        string title = args.Length > 0 ? args[0] : "Tao Backend";
        int waitMs = args.Length > 1 ? int.Parse(args[1]) : 30000;
        var uia = new CUIAutomation();
        var root = uia.GetRootElement();
        var walker = uia.RawViewWalker;
        IUIAutomationElement target = null;
        var child = walker.GetFirstChildElement(root);
        while (child != null) {
            try {
                string n = child.CurrentName ?? "";
                if (n.ToLower().Contains(title.ToLower())) { target = child; break; }
            } catch {}
            child = walker.GetNextSiblingElement(child);
        }
        if (target == null) { Console.WriteLine($"Window '{title}' not found"); return 1; }
        Console.WriteLine($"Target: {target.CurrentName} (pid={target.CurrentProcessId})");

        var ph = new PropHandler();
        var fh = new FocusHandler();
        var sh = new StructHandler();
        int[] props = { 30005, 30045, 30086, 30047, 30079 };
        uia.AddPropertyChangedEventHandlerNativeArray(
            target, TreeScope.TreeScope_Subtree, null, ph, ref props[0], props.Length);
        uia.AddFocusChangedEventHandler(null, fh);
        uia.AddStructureChangedEventHandler(target, TreeScope.TreeScope_Subtree, null, sh);

        Console.WriteLine($"Subscribed. Waiting {waitMs}ms for events...");
        Thread.Sleep(waitMs);
        uia.RemoveAllEventHandlers();
        Console.WriteLine("Done.");
        return 0;
    }
}

}
