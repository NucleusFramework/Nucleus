package dev.nucleusframework.lab.probes.rendering.webview

/** What the WebView can be pointed at: a live site, or pages that measure the embedding. */
sealed interface WebPage {
    val label: String

    data class Remote(
        val url: String,
        override val label: String = url,
    ) : WebPage

    data class Local(
        override val label: String,
        val html: String,
    ) : WebPage

    companion object {
        val Presets: List<WebPage> =
            listOf(
                Remote("https://nucleusframework.dev", "nucleusframework.dev"),
                Local("Scroll HUD", SCROLL_HUD_HTML),
                Local("Pointer & focus", POINTER_HTML),
            )
    }
}

// No template literals in the JS below: `${` would be a Kotlin template.

/** A long page drawing its own HUD: what the page itself received from a trackpad or wheel. */
private val SCROLL_HUD_HTML =
    """
    <!doctype html><html><head><meta charset="utf-8"><style>
    body{margin:0;font:14px -apple-system,"Segoe UI",Cantarell,sans-serif;background:#111;color:#ddd}
    .row{padding:10px 16px;border-bottom:1px solid #222}.row:nth-child(even){background:#181818}
    #hud{position:fixed;top:8px;right:8px;background:rgba(0,0,0,.78);color:#9f9;padding:8px 10px;
         border-radius:8px;font:12px ui-monospace,Menlo,Consolas,monospace;white-space:pre}
    </style></head><body><div id="hud"></div>
    <script>
    var wheel=0,lastDy=0,lastMode=0,lastT=0,maxGap=0,bursts=0;
    var hud=document.getElementById('hud');
    function paint(){
      hud.textContent='scrollY   '+Math.round(window.scrollY)+'\n'+
                      'wheel ev  '+wheel+'   bursts '+bursts+'\n'+
                      'last dY   '+lastDy.toFixed(2)+'  (deltaMode '+lastMode+')\n'+
                      'max gap   '+maxGap+' ms (within a burst)';
    }
    window.addEventListener('wheel',function(e){
      var t=performance.now();
      if(!lastT||t-lastT>400){bursts++;}else{maxGap=Math.max(maxGap,Math.round(t-lastT));}
      lastT=t;wheel++;lastDy=e.deltaY;lastMode=e.deltaMode;paint();
    },{passive:true});
    window.addEventListener('scroll',paint,{passive:true});
    for(var i=0;i<300;i++){
      var d=document.createElement('div');d.className='row';
      d.textContent='Row '+('00'+i).slice(-3)+' - scroll here: the page must follow, keep its momentum, stop at the ends';
      document.body.appendChild(d);
    }
    paint();
    </script></body></html>
    """.trimIndent()

/** Hover, click and keyboard focus counters drawn by the page, for input routing into the view. */
private val POINTER_HTML =
    """
    <!doctype html><html><head><meta charset="utf-8"><style>
    body{margin:0;padding:24px;font:14px -apple-system,"Segoe UI",Cantarell,sans-serif;background:#0f172a;color:#e2e8f0}
    #pad{height:160px;border:2px dashed #475569;border-radius:12px;display:flex;align-items:center;justify-content:center}
    #pad:hover{background:#1e293b;border-color:#60a5fa}
    input{margin-top:16px;width:320px;padding:8px;border-radius:6px;border:1px solid #475569;background:#020617;color:#e2e8f0}
    pre{font:12px ui-monospace,Menlo,Consolas,monospace;color:#9f9}
    </style></head><body>
    <div id="pad">hover, click, double-click here</div>
    <input id="field" placeholder="type here: focus must move into the page">
    <pre id="log"></pre>
    <script>
    var c={enter:0,leave:0,click:0,dbl:0,keys:0,focus:0,blur:0};
    function paint(){document.getElementById('log').textContent=JSON.stringify(c,null,1);}
    var pad=document.getElementById('pad'),field=document.getElementById('field');
    pad.addEventListener('mouseenter',function(){c.enter++;paint();});
    pad.addEventListener('mouseleave',function(){c.leave++;paint();});
    pad.addEventListener('click',function(){c.click++;paint();});
    pad.addEventListener('dblclick',function(){c.dbl++;paint();});
    field.addEventListener('keydown',function(){c.keys++;paint();});
    field.addEventListener('focus',function(){c.focus++;paint();});
    field.addEventListener('blur',function(){c.blur++;paint();});
    paint();
    </script></body></html>
    """.trimIndent()
