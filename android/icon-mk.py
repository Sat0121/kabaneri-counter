# 筆文字（Yuji Boku, OFL-1.1）の「六」でアプリのアイコンを作る（1.2〜と同じ設定）
import asyncio, base64, pathlib
from playwright.async_api import async_playwright
INK='#16161a'
async def main():
    async with async_playwright() as p:
        b=await p.chromium.launch(executable_path="/usr/bin/google-chrome")
        pg=await b.new_page(viewport={"width":600,"height":600})
        pathlib.Path('mk.html').write_text('<html><head><link rel="stylesheet" href="node_modules/@fontsource/yuji-boku/400.css"></head><body></body></html>')
        await pg.goto('file://'+str(pathlib.Path('mk.html').resolve()))
        await pg.evaluate("""async()=>{await document.fonts.load("200px 'Yuji Boku'","六");await document.fonts.ready}""")
        ok=await pg.evaluate("document.fonts.check(\"200px 'Yuji Boku'\",'六')"); print('font loaded',ok)
        async def render(size,bg,fill,box):
            return await pg.evaluate("""([size,bg,fill,box])=>{const c=document.createElement('canvas');c.width=c.height=size;const g=c.getContext('2d');if(bg){g.fillStyle=bg;g.fillRect(0,0,size,size)}
  const fs=400;g.font=`${fs}px 'Yuji Boku'`;const m=g.measureText('六');const w=m.actualBoundingBoxLeft+m.actualBoundingBoxRight,h=m.actualBoundingBoxAscent+m.actualBoundingBoxDescent;
  const k=Math.min(size*box/w,size*box/h);g.save();g.translate(size/2,size/2);g.scale(k,k);g.fillStyle=fill;
  g.fillText('六',-(m.actualBoundingBoxRight-m.actualBoundingBoxLeft)/2,(m.actualBoundingBoxAscent-m.actualBoundingBoxDescent)/2);g.restore();
  return c.toDataURL('image/png').split(',')[1]}""",[size,bg,fill,box])
        out=pathlib.Path('out'); out.mkdir(exist_ok=True)
        (out/'ic_fg.png').write_bytes(base64.b64decode(await render(432,None,INK,0.46)))
        (out/'ic_mono.png').write_bytes(base64.b64decode(await render(432,None,'#000000',0.46)))
        (out/'preview.png').write_bytes(base64.b64decode(await render(432,'#f3efe6',INK,0.46)))
        await b.close()
asyncio.run(main())
