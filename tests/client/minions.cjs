const mf=require('mineflayer'),fs=require('node:fs'),assert=require('node:assert/strict')
const {Vec3}=require('vec3')
const wait=ms=>new Promise(r=>setTimeout(r,ms));const result={checks:[],messages:[]}
const check=(value,name)=>{assert.ok(value,name);result.checks.push(name);console.log('PASS',name)}
async function until(fn,name,ms=15000){const end=Date.now()+ms;while(Date.now()<end){if(fn())return;await wait(50)}throw Error('Timeout '+name)}
const a=mf.createBot({host:'127.0.0.1',port:25575,username:'NexoraTestA',auth:'offline',version:'1.21.11'})
let worldName='';for(const event of ['login','respawn'])a._client.on(event,p=>{worldName=p.worldState?.name||p.worldState?.worldName||p.worldName||worldName})
a.on('messagestr',m=>{result.messages.push(m);console.log(m)})
a.on('error',e=>console.error(e.message));a.on('kicked',r=>console.error(r))
const token=()=>a.inventory.items().find(i=>JSON.stringify(i).includes('nexora:minion_coal'))
const stand=()=>Object.values(a.entities).find(e=>e.name==='armor_stand'&&e.position.distanceTo(a.entity.position)<6)
async function interact(e){await a.activateEntityAt(e,e.position.offset(0,0.6,0));await wait(300)}
async function main(){
 await until(()=>a.entity,'spawn',30000);await wait(1000)
 check(!!token(),'crafted minion available after server restart')
 a.chat(process.argv.includes('--existing')?'/island go':'/island create');await wait(1800)
 if(a.currentWindow){const slot=a.currentWindow.slots.slice(0,45).findIndex(i=>i&&i.name==='grass_block');if(slot<0)throw Error('No island blueprint grass icon');await a.clickWindow(slot,0,0)}
 await until(()=>worldName.includes('bskyblock'),'island created',30000);await wait(1000)
 check(worldName.includes('bskyblock'),'real BentoBox island created')
 const floor=a.blockAt(a.entity.position.floored().offset(0,-1,0));check(!!floor&&floor.boundingBox==='block','solid island placement floor')
 await a.equip(token(),'off-hand');a.setQuickBarSlot(0);await wait(200)
 a._client.write('block_place',{location:floor.position,direction:1,hand:1,cursorX:0.5,cursorY:1,cursorZ:0.5,insideBlock:false,sequence:0,worldBorderHit:false})
 await until(()=>stand(),'offhand minion spawn');await wait(200)
 check(!token(),'offhand placement consumes exactly one minion token')
 let entity=stand();await interact(entity);await until(()=>a.currentWindow,'minion management GUI')
 check(a.currentWindow.slots[7]?.name==='gold_ingot','existing minion upgrade action visible')
 await a.clickWindow(7,0,0);await wait(500)
 check(result.messages.some(m=>m.includes('Tier 2')||m.includes('tier 2')||m.includes('tier II')),'tier upgrade succeeds')
 if(a.currentWindow)a.closeWindow(a.currentWindow)
 a.setControlState('sneak',true);await wait(200);await interact(entity);a.setControlState('sneak',false)
 await until(()=>token(),'portable upgraded token')
 result.portableToken=token();check(JSON.stringify(token()).includes('minion_tier'),'portable token retains tier metadata')
 check(result.messages.some(m=>m.includes('picked up')),'owner can pick up empty upgraded minion')
 result.passed=true
}
main().catch(error=>{result.error=error.stack;result.passed=false;process.exitCode=1;console.error(error)}).finally(()=>{a.quit();fs.writeFileSync(process.env.NEXORA_CLIENT_RESULT||'/tmp/nexora-minion-client.json',JSON.stringify(result,null,2));setTimeout(()=>process.exit(process.exitCode||0),500)})
