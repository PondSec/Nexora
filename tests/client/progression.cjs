// Only target an SSH-forwarded isolated staging listener. Seed fixtures through its console.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const mineflayer = require('mineflayer')
const delay = ms => new Promise(r => setTimeout(r, ms))
const evidence = { checks: [], messages: {} }
const verify = (ok, name) => { assert.ok(ok, name); evidence.checks.push(name); console.log('PASS', name) }
async function until(predicate, name, timeout=15000) {
  const end=Date.now()+timeout
  while(Date.now()<end) { if(predicate())return; await delay(50) }
  throw new Error('Timeout: '+name)
}
function connect(username) {
  const bot=mineflayer.createBot({host:'127.0.0.1',port:25575,username,auth:'offline',version:'1.21.11'})
  evidence.messages[username] ??= []
  bot.on('messagestr',m=>{evidence.messages[username].push(m);console.log(username,m);if(m.includes('NXSMOKE_SEEDED'))bot.seeded=true})
  bot.on('error',error=>console.error(username,error.message))
  bot.on('kicked',reason=>console.error('KICK',username,reason))
  return bot
}
const json = item => JSON.stringify(item ?? {})
const has = (item,value) => json(item).includes(value)
const count = (bot,name) => bot.inventory.items().filter(i=>i.name===name).reduce((a,i)=>a+i.count,0)
const customCount = (bot,id) => bot.inventory.items().filter(i=>has(i,id)).reduce((a,i)=>a+i.count,0)
async function book(bot,id) {
  if(bot.currentWindow)bot.closeWindow(bot.currentWindow)
  bot.chat('/recipebook');await until(()=>bot.currentWindow,'recipe book');await delay(250)
  for(let page=0;page<5;page++) {
    const slot=bot.currentWindow.slots.slice(0,45).findIndex(i=>has(i,'recipe:'+id)||has(i,'locked:'+id))
    if(slot>=0)return {slot,locked:has(bot.currentWindow.slots[slot],'locked:'+id)}
    await bot.clickWindow(53,0,0);await delay(400)
  }
  throw new Error('Recipe not found: '+id)
}
async function craft(bot,id) {
  const found=await book(bot,id);verify(!found.locked,id+' unlocked')
  await bot.clickWindow(found.slot,0,0);await delay(350)
  verify(has(bot.currentWindow.slots[46],'craft:'+id+':1:'),'real craft button '+id)
  await bot.clickWindow(46,0,0);await delay(400)
  bot.closeWindow(bot.currentWindow)
}
let a,b
async function main() {
  a=connect('NexoraTestA');b=connect('NexoraTestB')
  await until(()=>a.entity&&b.entity,'both clients spawned',30000)
  if(process.argv.includes('--verify-restart')) {
    await delay(1200)
    verify(customCount(a,'nexora:minion_coal')===1,'crafted minion survives server restart')
    verify(customCount(b,'nexora:enchanted_coal')===1,'traded custom item survives server restart')
    verify(count(b,'coal')===168,'other profile materials survive restart')
  } else {
    console.log('READY_FOR_CONSOLE_SEED')
    await until(()=>a.seeded&&b.seeded,'console fixture seed',60000)
    a.quit();b.quit();await delay(600)
    a=connect('NexoraTestA');b=connect('NexoraTestB');await until(()=>a.entity&&b.entity,'reconnect');await delay(1500)
    verify(count(a,'coal')===168&&count(b,'coal')===168,'separate inventories survive reconnect')
    let locked=await book(b,'nexora:compress_coal');verify(locked.locked,'other profile collection remains locked')
    await b.clickWindow(locked.slot,0,0);await delay(250)
    verify(!has(b.currentWindow.slots[46],'craft:nexora:compress_coal'),'locked recipe has no enabled craft action');b.closeWindow(b.currentWindow)
    await craft(a,'nexora:compress_coal')
    verify(count(a,'coal')===8&&customCount(a,'nexora:enchanted_coal')===1,'exact 160 ingredient consumption and canonical output')
    a.chat('/bazaar sell nexora:enchanted_coal 1 1');await until(()=>customCount(a,'nexora:enchanted_coal')===0,'sell escrow removes custom item')
    await delay(300);b.chat('/bazaar buy nexora:enchanted_coal 1 1');await until(()=>customCount(b,'nexora:enchanted_coal')===1,'buyer receives canonical custom item')
    verify(customCount(a,'nexora:enchanted_coal')===0,'seller has no duplicate after fill')
    b.chat('/bazaar');await until(()=>b.currentWindow,'bazaar claim GUI');await b.clickWindow(46,0,0);await delay(300)
    verify(customCount(b,'nexora:enchanted_coal')===1,'claim replay does not duplicate item');b.closeWindow(b.currentWindow)
    locked=await book(b,'nexora:compress_coal');verify(locked.locked,'buying materials does not unlock collection');b.closeWindow(b.currentWindow)
    await craft(a,'nexora:minion_coal')
    verify(count(a,'coal')===0&&customCount(a,'nexora:minion_coal')===1,'functional minion recipe consumes eight coal and chassis')
    const found=await book(a,'nexora:minion_coal');await a.clickWindow(found.slot,0,0);await delay(250)
    verify(!has(a.currentWindow.slots[46],'craft:nexora:minion_coal'),'missing materials disable repeat crafting');a.closeWindow(a.currentWindow)
  }
  evidence.passed=true
}
main().catch(error=>{evidence.passed=false;evidence.error=error.stack;process.exitCode=1;console.error(error)}).finally(()=>{
  if(a)a.quit();if(b)b.quit()
  fs.writeFileSync(process.env.NEXORA_CLIENT_RESULT||'/tmp/nexora-client-result.json',JSON.stringify(evidence,null,2))
  setTimeout(()=>process.exit(process.exitCode||0),500)
})
