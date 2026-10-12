const mf=require('mineflayer'),fs=require('node:fs'),assert=require('node:assert/strict')
const {Vec3}=require('vec3')
const wait=ms=>new Promise(r=>setTimeout(r,ms));const result={checks:[],messages:[]}
const check=(value,name)=>{assert.ok(value,name);result.checks.push(name);console.log('PASS',name)}
async function until(fn,name,ms=15000){const end=Date.now()+ms;while(Date.now()<end){if(fn())return;await wait(50)}throw Error('Timeout '+name)}
const a=mf.createBot({host:'127.0.0.1',port:25575,username:'NexoraTestA',auth:'offline',version:'1.21.11'})
let visitor;
let worldName='';for(const event of ['login','respawn'])a._client.on(event,p=>{worldName=p.worldState?.name||p.worldState?.worldName||p.worldName||worldName})
a.on('messagestr',m=>{result.messages.push(m);console.log(m)})
a.on('error',e=>console.error(e.message));a.on('kicked',r=>console.error(r))
const token=()=>a.inventory.items().find(i=>JSON.stringify(i).includes('nexora:minion_coal'))
const stand=()=>Object.values(a.entities).find(e=>e.name==='armor_stand'&&e.position.distanceTo(a.entity.position)<6)
async function interact(e){await a.activateEntityAt(e,e.position.offset(0,0.6,0));await wait(300)}
async function main(){
 await until(()=>a.entity,'spawn',30000);await wait(1000)
 const placed=process.argv.includes('--placed');
 if(!placed)check(!!token(),'crafted minion available after server restart')
 a.chat(process.argv.includes('--existing')?'/island go':'/island create');await wait(1800)
 if(a.currentWindow){const slot=a.currentWindow.slots.slice(0,45).findIndex(i=>i&&i.name==='grass_block');if(slot<0)throw Error('No island blueprint grass icon');await a.clickWindow(slot,0,0)}
 await until(()=>result.messages.some(m=>m.includes('Now entering your island:')),'island created',30000);await wait(1000)
 check(result.messages.some(m=>m.includes('Now entering your island:')),'real BentoBox island created')
 let entity;
 if(!placed) {
 const floor=a.blockAt(a.entity.position.floored().offset(0,-1,0));check(!!floor&&floor.boundingBox==='block','solid island placement floor')
 await a.equip(token(),'off-hand');a.setQuickBarSlot(0);await wait(200)
 a._client.write('block_place',{location:floor.position,direction:1,hand:1,cursorX:0.5,cursorY:1,cursorZ:0.5,insideBlock:false,sequence:0,worldBorderHit:false})
 await until(()=>stand(),'offhand minion spawn');await wait(200)
 check(!token(),'offhand placement consumes exactly one minion token')
 entity=stand();await interact(entity);await until(()=>a.currentWindow,'minion management GUI')
 check(a.currentWindow.slots[7]?.name==='gold_ingot','existing minion upgrade action visible')
 if(!process.argv.includes('--tier2')){await a.clickWindow(7,0,0);await wait(500);check(result.messages.some(m=>m.includes('tier 2')),'tier upgrade succeeds')}
 } else {
 await until(()=>stand(),'placed minion restored after restart');entity=stand();check(!!entity,'placed minion restored after full Paper restart')
 await interact(entity);await until(()=>a.currentWindow,'restored management GUI');check(JSON.stringify(a.currentWindow.slots[7]).includes('tier 3'),'restored minion retains tier II')
 await a.clickWindow(6,0,0);await wait(300)
 }
 if(process.argv.includes('--foreign')) {
  visitor=mf.createBot({host:'127.0.0.1',port:25575,username:'NexoraTestB',auth:'offline',version:'1.21.11'})
  const messages=[];visitor.on('messagestr',m=>messages.push(m));visitor.on('error',e=>console.error(e.message))
  await until(()=>visitor.entity,'visitor spawn',30000);await wait(1200)
  // Teleport solely through the isolated staging console, without granting island membership.
  require('node:child_process').execFileSync('ssh',['marc-a2',"printf 'tp NexoraTestB NexoraTestA\\n' > /home/rescueadmin/nexora-staging-20261012/console.pipe"])
  await until(()=>visitor.entity.position.distanceTo(a.entity.position)<3,'visitor console teleport');await wait(300)
  const target=Object.values(visitor.entities).find(e=>e.name==='armor_stand'&&e.position.distanceTo(visitor.entity.position)<6)
  check(!!target,'visitor observes the same placed minion')
  const before=visitor.inventory.items().filter(i=>JSON.stringify(i).includes('nexora:minion_coal')).reduce((n,i)=>n+i.count,0)
  await visitor.activateEntityAt(target,target.position.offset(0,.6,0));await wait(400)
  check(!visitor.currentWindow,'foreign player cannot open owner minion management')
  visitor._client.write('use_entity',{target:target.id,mouse:2,sneaking:true,hand:0,x:0,y:.6,z:0,location:new Vec3(0,.6,0)});await wait(400)
  check(visitor.inventory.items().filter(i=>JSON.stringify(i).includes('nexora:minion_coal')).reduce((n,i)=>n+i.count,0)===before,'foreign pickup cannot receive a minion token')
  check(!!stand(),'foreign interaction cannot remove the owner minion')
  result.visitorMessages=messages;visitor.quit();visitor=null
 }
 if(a.currentWindow)a.closeWindow(a.currentWindow)
 a.setControlState('sneak',true);await wait(200);a._client.write('use_entity',{target:entity.id,mouse:2,sneaking:true,hand:0,x:0,y:0.6,z:0,location:new Vec3(0,0.6,0)});await wait(300);a.setControlState('sneak',false)
 await until(()=>token(),'portable upgraded token')
 result.portableToken=token();const data=token().components.find(c=>c.type==='custom_data').data.value.PublicBukkitValues.value;check(data['nexoracore:minion_tier'].value===2,'portable token retains exact tier II metadata')
 check(result.messages.some(m=>m.includes('picked up')),'owner can pick up empty upgraded minion')
 result.passed=true
}
main().catch(error=>{result.error=error.stack;result.passed=false;process.exitCode=1;console.error(error)}).finally(()=>{if(visitor)visitor.quit();a.quit();fs.writeFileSync(process.env.NEXORA_CLIENT_RESULT||'/tmp/nexora-minion-client.json',JSON.stringify(result,null,2));setTimeout(()=>process.exit(process.exitCode||0),500)})
