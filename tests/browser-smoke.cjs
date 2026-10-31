const {chromium}=require('C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',args:['--no-proxy-server']});
 const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://127.0.0.1:8080/',{waitUntil:'networkidle',timeout:20000});
 await page.waitForSelector('#cash');
 console.log(JSON.stringify({title:await page.title(),heading:await page.locator('h1').textContent(),cash:await page.locator('#cash').textContent(),sync:await page.locator('#sync-status').textContent(),errors}));
 await page.screenshot({path:'tests/preview.png',fullPage:true});
 await browser.close();if(errors.length)process.exitCode=1;
})().catch(e=>{console.error(e.message);process.exitCode=1});
