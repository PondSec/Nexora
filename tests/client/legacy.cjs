const mf=require('mineflayer'),fs=require('node:fs'),cp=require('node:child_process'),assert=require('node:assert/strict')
const wait=ms=>new Promise(r=>setTimeout(r,ms));let bot
const phase=process.argv[2];assert.ok(['pending','delivered','partial'].includes(phase))
const result={phase,checks:[],messages:[]}
const check=(ok,name)=>{assert.ok(ok,name);result.checks.push(name);console.log('PASS',name)}
async function until(fn,name,ms=20000){const end=Date.now()+ms;while(Date.now()<end){if(fn())return;await wait(50)}throw Error('Timeout '+name)}
function connect(){const b=mf.createBot({host:'127.0.0.1',port:25575,username:'NexoraLegacy',auth:'offline',version:'1.21.11'});b.on('messagestr',m=>result.messages.push(m));b.on('error',e=>console.error(e.message));return b}
const count=()=>bot.inventory.items().filter(i=>i.name==='coal').reduce((n,i)=>n+i.count,0)
const tagged=()=>bot.inventory.items().some(i=>JSON.stringify(i).includes('nexoratrading:market_claim_id'))
async function main(){
 bot=connect();await until(()=>bot.entity,'spawn');await wait(1000)
 cp.execFileSync('ssh',['marc-a2',"printf 'nxsmoke legacy "+phase+"\\n' > /home/rescueadmin/nexora-staging-20261012/console.pipe"])
 await until(()=>result.messages.includes('NXLEGACY_SEEDED'),'fixture seed')
 check(count()===(phase==='partial'?3:7)&&tagged(),'legacy inventory contains exact original tagged items')
 bot.quit();await wait(500);bot=connect();await until(()=>bot.entity,'reconnect');await wait(1000)
 if(phase==='delivered'){
  await until(()=>!tagged(),'claimed tag normalization')
  check(count()===7,'already delivered legacy item normalized without changing quantity')
 }else{
  bot.chat('/bazaar');await until(()=>bot.currentWindow,'claim menu');await bot.clickWindow(46,0,0);await wait(500)
  if(phase==='partial'){
   check(count()===7&&!tagged(),'partial legacy delivery preserves original three and delivers exactly four missing items')
   await bot.clickWindow(46,0,0);await wait(300);check(count()===7,'partial legacy reconciliation cannot be replayed for extra items')
  }else{
   check(count()===7&&!tagged(),'pending save-before-ack acknowledged without a second payout')
   await bot.clickWindow(46,0,0);await wait(300);check(count()===7,'repeated legacy claim cannot duplicate')
  }
 }
 result.passed=true
}
main().catch(e=>{result.passed=false;result.error=e.stack;process.exitCode=1;console.error(e)}).finally(()=>{if(bot)bot.quit();fs.writeFileSync(process.env.NEXORA_CLIENT_RESULT||'/tmp/nexora-legacy-client.json',JSON.stringify(result,null,2));setTimeout(()=>process.exit(process.exitCode||0),500)})
