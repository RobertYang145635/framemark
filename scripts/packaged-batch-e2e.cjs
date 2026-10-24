const path=require('node:path'); const fs=require('node:fs'); const { _electron: electron }=require('playwright-core')
const outDir='C:/Users/YHYTRUMP/Pictures/FrameMark'
function list(){return fs.existsSync(outDir)?fs.readdirSync(outDir):[]}
async function waitDone(p){await p.waitForSelector('.export-card',{timeout:30000});const t0=Date.now();for(let i=0;i<300;i++){await p.waitForTimeout(700);const t=await p.locator('.export-card').innerText().catch(()=> '');if(t.includes('导出完成')||t.includes('失败'))return {t,sec:(Date.now()-t0)/1000}}throw new Error('timeout')}
;(async()=>{
 const app=await electron.launch({executablePath:'D:/border&watermark/framemark/release/FrameMark-win32-x64/FrameMark.exe'})
 const p=await app.firstWindow(); const errs=[]; p.on('pageerror',e=>errs.push(e.message))
 await p.waitForSelector('.app-shell.ready',{timeout:30000});
 await p.evaluate(()=>{localStorage.clear();indexedDB.deleteDatabase('framemark')}); await p.reload();
 await p.waitForSelector('.app-shell.ready',{timeout:30000}); await p.waitForTimeout(800)
 const before=list()
 await p.setInputFiles('#framemark-file-input',[
  'D:/border&watermark/_research/test-assets/p1170883.jpg',
  'D:/border&watermark/_research/test-assets/p1181817.jpg',
  'D:/border&watermark/_research/test-assets/vertical.jpg'])
 await p.waitForSelector('.thumb',{timeout:120000}); await p.waitForTimeout(3500)
 await p.locator('.inspector-tabs button',{hasText:'导出'}).click(); await p.waitForTimeout(400)
 await p.locator('.inspector-pane .segmented button',{hasText:'JPG'}).first().click(); await p.waitForTimeout(300)
 await p.locator('.export-actions button',{hasText:'导出全部'}).click()
 const a=await waitDone(p); console.log('batch',a.sec.toFixed(1)+'s',a.t.replace(/\n+/g,' | '))
 await p.locator('.export-card button',{hasText:'完成'}).click(); await p.waitForTimeout(400)
 const afterBatch=list(); const newBatch=afterBatch.filter(f=>!before.includes(f)); console.log('new batch files',newBatch)
 await p.locator('.thumb-main').nth(1).click(); await p.waitForTimeout(1500)
 await p.locator('.inspector-tabs button',{hasText:'导出'}).click(); await p.waitForTimeout(300)
 await p.locator('.inspector-pane .segmented button',{hasText:'TIFF'}).first().click(); await p.waitForTimeout(300)
 await p.locator('.export-actions button',{hasText:'导出当前'}).click()
 const b=await waitDone(p); console.log('tiff',b.sec.toFixed(1)+'s',b.t.replace(/\n+/g,' | '))
 await p.locator('.export-card button',{hasText:'完成'}).click(); await p.waitForTimeout(300)
 const afterTiff=list(); const newTiff=afterTiff.filter(f=>!afterBatch.includes(f)); console.log('new tiff files',newTiff)
 console.log('page errors',errs)
 await app.close()
})().catch(e=>{console.error(e);process.exit(1)})
