// Console-controlled fault injection only on the loopback staging server.
const mf=require('mineflayer'),fs=require('node:fs'),assert=require('node:assert/strict')
const bot=mf.createBot({host:'127.0.0.1',port:25575,username:'NexoraCrash',auth:'offline',version:'1.21.11'})
const result={checks:[],messages:[]};const wait=ms=>new Promise(r=>setTimeout(r,ms))
bot.on('messagestr',m=>result.messages.push(m));bot.on('error',()=>{})
const check=(ok,name)=>{assert.ok(ok,name);result.checks.push(name);console.log('PASS',name)}
async function until(fn,name,ms=30000){const end=Date.now()+ms;while(Date.now()<end){if(fn())return;await wait(50)}throw Error('Timeout '+name)}
async function main(){
 await until(()=>bot.entity,'spawn');await wait(1500)
 const phase=process.argv[2]
 if(phase==='arm'){
  console.log('READY_FOR_CRASH_INJECTION')
  let ended=false;bot.once('end',()=>ended=true);await until(()=>ended,'hard crash disconnect',60000)
  check(ended,'real client disconnected at injected JVM halt')
 }else{
  assert.ok(['before','after'].includes(phase),'expected before or after')
  const count=name=>bot.inventory.items().filter(i=>i.name===name).reduce((n,i)=>n+i.count,0)
  check(count('emerald')===(phase==='before'?7:0),'rollback retains original inventory only before business commit')
  check(count('diamond')===(phase==='after'?13:0),'committed inventory retained exactly once after business commit')
  result.inventory=bot.inventory.items()
 }
 result.passed=true
}
main().catch(e=>{result.passed=false;result.error=e.stack;process.exitCode=1;console.error(e)}).finally(()=>{
 bot.quit();fs.writeFileSync(process.env.NEXORA_CLIENT_RESULT||'/tmp/nexora-recovery-client.json',JSON.stringify(result,null,2));setTimeout(()=>process.exit(process.exitCode||0),500)
})
