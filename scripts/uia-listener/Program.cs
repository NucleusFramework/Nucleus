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
            var t = new Thread(() => Run(args));
            t.SetApartmentState(ApartmentState.MTA);
            t.Start();
            t.Join();
            return 0;
        }
        return Run(args);
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
