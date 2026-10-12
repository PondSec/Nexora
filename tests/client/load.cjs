// Read-only gameplay workload on isolated loopback staging; creates synthetic profiles.
const mf=require('mineflayer'),fs=require('node:fs'),cp=require('node:child_process'),assert=require('node:assert/strict')
const wait=ms=>new Promise(r=>setTimeout(r,ms));const bots=[]
const result={scope:'30 synthetic clients on isolated staging, menu workload only',clients:30,latenciesMillis:[],errors:[],diagnostics:[],messages:{}}
async function until(fn,name,ms=30000){const end=Date.now()+ms;while(Date.now()<end){if(fn())return;await wait(25)}throw Error('Timeout '+name)}
function diagnostic(){cp.execFileSync('ssh',['marc-a2',"printf 'nexora diagnostics\\n' > /home/rescueadmin/nexora-staging-20261012/console.pipe"])}
async function main(){
 for(let n=1;n<=30;n++){
  const username='NxLoad'+String(n).padStart(2,'0')
  const bot=mf.createBot({host:'127.0.0.1',port:25575,username,auth:'offline',version:'1.21.11'})
  result.messages[username]=[];bot.on('messagestr',m=>{const a=result.messages[username];a.push(m);if(a.length>12)a.shift()})
  bot.on('error',e=>result.errors.push({player:bot.username,error:e.message}));bot.on('kicked',r=>result.errors.push({player:bot.username,kick:r}))
  bots.push(bot);await wait(200)
 }
 await until(()=>bots.every(b=>b.entity),'all thirty spawned',60000);await wait(3000)
 console.log('THIRTY_CLIENTS_CONNECTED');diagnostic()
 const started=Date.now();let done=0
 await Promise.all(bots.map(async(bot,index)=>{
  await wait(index*30)
  for(let round=0;round<10;round++){
   if(bot.currentWindow)bot.closeWindow(bot.currentWindow)
   const now=Date.now();bot.chat(['/recipebook','/profile','/bank','/bazaar'][round%4])
   await until(()=>bot.currentWindow,'menu '+bot.username+' round '+round,10000)
   result.latenciesMillis.push(Date.now()-now);done++
   await wait(1000)
  }
 }))
 diagnostic();await wait(500)
 result.durationMillis=Date.now()-started;result.menuOperations=done
 assert.equal(done,300);assert.equal(result.errors.length,0,JSON.stringify(result.errors))
 result.latenciesMillis.sort((a,b)=>a-b)
 result.p95ClientMenuMillis=result.latenciesMillis[Math.ceil(result.latenciesMillis.length*.95)-1]
 result.maxClientMenuMillis=result.latenciesMillis.at(-1);result.passed=true
 console.log('PASS thirty clients, 300 menu opens, p95='+result.p95ClientMenuMillis+'ms')
}
main().catch(e=>{result.passed=false;result.error=e.stack;process.exitCode=1;console.error(e)}).finally(()=>{
 bots.forEach(b=>b.quit());fs.writeFileSync(process.env.NEXORA_CLIENT_RESULT||'/tmp/nexora-load-client.json',JSON.stringify(result,null,2));setTimeout(()=>process.exit(process.exitCode||0),500)
})
