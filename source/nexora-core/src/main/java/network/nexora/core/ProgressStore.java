package network.nexora.core;

import java.io.File;
import java.sql.*;
import java.util.*;

/** SQLite-backed UUID profile and progression store. All monetary values are integer minor units. */
final class ProgressStore implements AutoCloseable {
    private final Connection connection;

    record MinionRow(String id, UUID owner, String islandId, String world, int x, int y, int z,
                     String type, int tier, int storage, long lastAction) {}
    record GatherResult(int skillLevelBefore, int skillLevelAfter, int collectionMilestones,
                        long skyXpAwarded, long collectionCount) {}
    record DailyTask(String quest, long progress, boolean claimed) {}
    record BlockRestore(String world, int x, int y, int z, String blockData, long restoreAt) {}

    ProgressStore(File file, long startingBalanceMinor) throws SQLException {
        file.getParentFile().mkdirs();
        org.sqlite.SQLiteConfig sqlite = new org.sqlite.SQLiteConfig();
        sqlite.setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath(), sqlite.toProperties());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=10000");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("CREATE TABLE IF NOT EXISTS profiles(uuid TEXT PRIMARY KEY, username TEXT NOT NULL, first_join INTEGER NOT NULL, last_join INTEGER NOT NULL, balance_minor INTEGER NOT NULL CHECK(balance_minor>=0), total_earned_minor INTEGER NOT NULL DEFAULT 0, skyblock_xp INTEGER NOT NULL DEFAULT 0 CHECK(skyblock_xp>=0))");
            s.execute("CREATE TABLE IF NOT EXISTS skills(uuid TEXT NOT NULL REFERENCES profiles(uuid), skill TEXT NOT NULL, xp INTEGER NOT NULL DEFAULT 0 CHECK(xp>=0), PRIMARY KEY(uuid,skill))");
            s.execute("CREATE TABLE IF NOT EXISTS collections(uuid TEXT NOT NULL REFERENCES profiles(uuid), item TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0 CHECK(amount>=0), PRIMARY KEY(uuid,item))");
            s.execute("CREATE TABLE IF NOT EXISTS economy_ledger(id TEXT PRIMARY KEY, uuid TEXT NOT NULL, delta_minor INTEGER NOT NULL, reason TEXT NOT NULL, created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS minions(id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL REFERENCES profiles(uuid), island_id TEXT NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, type TEXT NOT NULL, tier INTEGER NOT NULL CHECK(tier BETWEEN 1 AND 11), storage INTEGER NOT NULL DEFAULT 0 CHECK(storage>=0), last_action INTEGER NOT NULL, UNIQUE(world,x,y,z))");
            s.execute("CREATE TABLE IF NOT EXISTS minion_inventory_receipts(operation_key TEXT PRIMARY KEY,owner_uuid TEXT NOT NULL REFERENCES profiles(uuid),minion_id TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(quantity>=0),created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS progression_claims(uuid TEXT NOT NULL REFERENCES profiles(uuid), claim_key TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(uuid,claim_key))");
            s.execute("CREATE TABLE IF NOT EXISTS placed_blocks(world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,uuid TEXT NOT NULL,material TEXT NOT NULL,PRIMARY KEY(world,x,y,z))");
            s.execute("CREATE TABLE IF NOT EXISTS daily_quest_progress(uuid TEXT NOT NULL REFERENCES profiles(uuid),day_key TEXT NOT NULL,quest_id TEXT NOT NULL,progress INTEGER NOT NULL DEFAULT 0 CHECK(progress>=0),claimed INTEGER NOT NULL DEFAULT 0 CHECK(claimed IN (0,1)),PRIMARY KEY(uuid,day_key,quest_id))");
            s.execute("CREATE TABLE IF NOT EXISTS block_regeneration(world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,block_data TEXT NOT NULL,restore_at INTEGER NOT NULL,PRIMARY KEY(world,x,y,z))");
        }
        ensureColumn("profiles", "skyblock_xp", "INTEGER NOT NULL DEFAULT 0 CHECK(skyblock_xp>=0)");
        this.startingBalanceMinor = startingBalanceMinor;
    }

    private final long startingBalanceMinor;

    synchronized void scheduleBlockRestore(String world, int x, int y, int z, String blockData, long restoreAt) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("INSERT INTO block_regeneration(world,x,y,z,block_data,restore_at) VALUES(?,?,?,?,?,?) ON CONFLICT(world,x,y,z) DO UPDATE SET block_data=excluded.block_data,restore_at=excluded.restore_at")) {
            p.setString(1, world); p.setInt(2, x); p.setInt(3, y); p.setInt(4, z); p.setString(5, blockData); p.setLong(6, restoreAt); p.executeUpdate();
        }
    }

    synchronized List<BlockRestore> pendingBlockRestores() throws SQLException {
        List<BlockRestore> rows = new ArrayList<>();
        try (PreparedStatement p = connection.prepareStatement("SELECT world,x,y,z,block_data,restore_at FROM block_regeneration"); ResultSet r = p.executeQuery()) {
            while (r.next()) rows.add(new BlockRestore(r.getString(1), r.getInt(2), r.getInt(3), r.getInt(4), r.getString(5), r.getLong(6)));
        }
        return rows;
    }

    synchronized List<BlockRestore> dueBlockRestores(long now, int limit) throws SQLException {
        List<BlockRestore> rows = new ArrayList<>();
        try (PreparedStatement p = connection.prepareStatement("SELECT world,x,y,z,block_data,restore_at FROM block_regeneration WHERE restore_at<=? ORDER BY restore_at LIMIT ?")) {
            p.setLong(1, now); p.setInt(2, limit);
            try (ResultSet r = p.executeQuery()) { while (r.next()) rows.add(new BlockRestore(r.getString(1), r.getInt(2), r.getInt(3), r.getInt(4), r.getString(5), r.getLong(6))); }
        }
        return rows;
    }

    synchronized void finishBlockRestore(BlockRestore row) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("DELETE FROM block_regeneration WHERE world=? AND x=? AND y=? AND z=? AND restore_at=?")) {
            p.setString(1, row.world()); p.setInt(2, row.x()); p.setInt(3, row.y()); p.setInt(4, row.z()); p.setLong(5, row.restoreAt()); p.executeUpdate();
        }
    }

    synchronized void join(UUID uuid, String name) throws SQLException {
        long now=System.currentTimeMillis();
        try (PreparedStatement p=connection.prepareStatement("INSERT INTO profiles(uuid,username,first_join,last_join,balance_minor) VALUES(?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET username=excluded.username,last_join=excluded.last_join")) {
            p.setString(1,uuid.toString()); p.setString(2,name); p.setLong(3,now); p.setLong(4,now); p.setLong(5,startingBalanceMinor); p.executeUpdate();
        }
        for (String skill: List.of("mining","farming","foraging","combat","fishing","enchanting","alchemy")) try(PreparedStatement p=connection.prepareStatement("INSERT OR IGNORE INTO skills(uuid,skill) VALUES(?,?)")){p.setString(1,uuid.toString());p.setString(2,skill);p.executeUpdate();}
    }
    synchronized long balance(UUID id) throws SQLException { try(PreparedStatement p=connection.prepareStatement("SELECT balance_minor FROM profiles WHERE uuid=?")){p.setString(1,id.toString());try(ResultSet r=p.executeQuery()){return r.next()?r.getLong(1):0;}} }
    synchronized long xp(UUID id,String skill) throws SQLException { try(PreparedStatement p=connection.prepareStatement("SELECT xp FROM skills WHERE uuid=? AND skill=?")){p.setString(1,id.toString());p.setString(2,skill);try(ResultSet r=p.executeQuery()){return r.next()?r.getLong(1):0;}} }
    synchronized long skyblockXp(UUID id) throws SQLException { try(PreparedStatement p=connection.prepareStatement("SELECT skyblock_xp FROM profiles WHERE uuid=?")){p.setString(1,id.toString());try(ResultSet r=p.executeQuery()){return r.next()?r.getLong(1):0;}} }
    private void ensureColumn(String table,String column,String definition)throws SQLException {
        boolean found=false;try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")){while(r.next())if(column.equalsIgnoreCase(r.getString("name")))found=true;}
        if(!found)try(Statement s=connection.createStatement()){s.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+definition);}
    }
    synchronized Map<String,Long> skills(UUID id) throws SQLException { Map<String,Long> m=new LinkedHashMap<>();try(PreparedStatement p=connection.prepareStatement("SELECT skill,xp FROM skills WHERE uuid=? ORDER BY skill")){p.setString(1,id.toString());try(ResultSet r=p.executeQuery()){while(r.next())m.put(r.getString(1),r.getLong(2));}}return m; }
    synchronized Map<String,Long> collections(UUID id) throws SQLException { Map<String,Long> m=new LinkedHashMap<>();try(PreparedStatement p=connection.prepareStatement("SELECT item,amount FROM collections WHERE uuid=? ORDER BY item")){p.setString(1,id.toString());try(ResultSet r=p.executeQuery()){while(r.next())m.put(r.getString(1),r.getLong(2));}}return m; }
    synchronized boolean withdraw(UUID id,long amountMinor,String reason) throws SQLException {
        if(amountMinor<0) return false; connection.setAutoCommit(false);
        try { try(PreparedStatement p=connection.prepareStatement("UPDATE profiles SET balance_minor=balance_minor-? WHERE uuid=? AND balance_minor>=?")){p.setLong(1,amountMinor);p.setString(2,id.toString());p.setLong(3,amountMinor);if(p.executeUpdate()!=1){connection.rollback();return false;}}
            ledger(id,-amountMinor,reason);connection.commit();return true;
        } catch(SQLException|RuntimeException e){connection.rollback();throw e;} finally{connection.setAutoCommit(true);}
    }
    synchronized long chargeDeathHalf(UUID id)throws SQLException{
        connection.setAutoCommit(false);
        try{
            long balance;try(PreparedStatement p=connection.prepareStatement("SELECT balance_minor FROM profiles WHERE uuid=?")){p.setString(1,id.toString());try(ResultSet r=p.executeQuery()){if(!r.next()){connection.rollback();return 0;}balance=r.getLong(1);}}
            long loss=balance/2;
            if(loss>0){try(PreparedStatement p=connection.prepareStatement("UPDATE profiles SET balance_minor=balance_minor-? WHERE uuid=? AND balance_minor>=?")){p.setLong(1,loss);p.setString(2,id.toString());p.setLong(3,loss);if(p.executeUpdate()!=1)throw new SQLException("Death purse update failed");}ledger(id,-loss,"Death purse loss (50%)");}
            connection.commit();return loss;
        }catch(SQLException|RuntimeException e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
    }
    synchronized boolean deposit(UUID id,long amountMinor,String reason) throws SQLException {
        if(amountMinor<0)return false;connection.setAutoCommit(false);
        try{try(PreparedStatement p=connection.prepareStatement("UPDATE profiles SET balance_minor=balance_minor+?,total_earned_minor=total_earned_minor+? WHERE uuid=? AND balance_minor<=? AND total_earned_minor<=?")){p.setLong(1,amountMinor);p.setLong(2,amountMinor);p.setString(3,id.toString());p.setLong(4,Long.MAX_VALUE-amountMinor);p.setLong(5,Long.MAX_VALUE-amountMinor);if(p.executeUpdate()!=1){connection.rollback();return false;}}
            ledger(id,amountMinor,reason);connection.commit();return true;
        }catch(SQLException|RuntimeException e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
    }
    private void ledger(UUID id,long delta,String reason)throws SQLException{try(PreparedStatement p=connection.prepareStatement("INSERT INTO economy_ledger(id,uuid,delta_minor,reason,created_at) VALUES(?,?,?,?,?)")){p.setString(1,UUID.randomUUID().toString());p.setString(2,id.toString());p.setLong(3,delta);p.setString(4,reason);p.setLong(5,System.currentTimeMillis());p.executeUpdate();}}
    synchronized void addProgress(UUID id,String skill,String item,long xp,long amount)throws SQLException {
        connection.setAutoCommit(false);try{if(xp>0)try(PreparedStatement p=connection.prepareStatement("INSERT INTO skills(uuid,skill,xp) VALUES(?,?,?) ON CONFLICT(uuid,skill) DO UPDATE SET xp=xp+excluded.xp WHERE xp<=?")){p.setString(1,id.toString());p.setString(2,skill);p.setLong(3,xp);p.setLong(4,Long.MAX_VALUE-xp);p.executeUpdate();}
            if(item!=null&&amount>0)try(PreparedStatement p=connection.prepareStatement("INSERT INTO collections(uuid,item,amount) VALUES(?,?,?) ON CONFLICT(uuid,item) DO UPDATE SET amount=amount+excluded.amount WHERE amount<=?")){p.setString(1,id.toString());p.setString(2,item);p.setLong(3,amount);p.setLong(4,Long.MAX_VALUE-amount);p.executeUpdate();}connection.commit();
        }catch(SQLException|RuntimeException e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
    }

    synchronized GatherResult recordGather(UUID id,String skill,String item,long xp,long amount,
                                            List<Long> collectionMilestones,long xpPerSkillLevel,long xpPerCollectionMilestone,
                                            ProgressionMath.Curve skillCurve,String questDay,Map<String,Long> dailyTargets)throws SQLException {
        connection.setAutoCommit(false);
        try {
            long oldSkill=0,oldCollection=0;
            try(PreparedStatement p=connection.prepareStatement("SELECT xp FROM skills WHERE uuid=? AND skill=?")){p.setString(1,id.toString());p.setString(2,skill);try(ResultSet r=p.executeQuery()){if(r.next())oldSkill=r.getLong(1);}}
            if(item!=null)try(PreparedStatement p=connection.prepareStatement("SELECT amount FROM collections WHERE uuid=? AND item=?")){p.setString(1,id.toString());p.setString(2,item);try(ResultSet r=p.executeQuery()){if(r.next())oldCollection=r.getLong(1);}}
            if(oldSkill>Long.MAX_VALUE-xp||oldCollection>Long.MAX_VALUE-amount)throw new SQLException("Progress counter overflow");
            long newSkill=oldSkill+xp,newCollection=oldCollection+amount;
            try(PreparedStatement p=connection.prepareStatement("INSERT INTO skills(uuid,skill,xp) VALUES(?,?,?) ON CONFLICT(uuid,skill) DO UPDATE SET xp=excluded.xp")){p.setString(1,id.toString());p.setString(2,skill);p.setLong(3,newSkill);p.executeUpdate();}
            if(item!=null&&amount>0)try(PreparedStatement p=connection.prepareStatement("INSERT INTO collections(uuid,item,amount) VALUES(?,?,?) ON CONFLICT(uuid,item) DO UPDATE SET amount=excluded.amount")){p.setString(1,id.toString());p.setString(2,item);p.setLong(3,newCollection);p.executeUpdate();}
            int skillBefore=ProgressionMath.skill(oldSkill,skillCurve).level(),skillAfter=ProgressionMath.skill(newSkill,skillCurve).level();long awarded=0;
            for(int level=skillBefore+1;level<=skillAfter;level++)if(claimAndAward(id,"skill:"+skill+":level:"+level,xpPerSkillLevel))awarded+=xpPerSkillLevel;
            int milestones=0;
            if(item!=null&&amount>0)for(long threshold:collectionMilestones)if(threshold>oldCollection&&threshold<=newCollection){
                if(claimAndAward(id,"collection:"+item+":"+threshold,xpPerCollectionMilestone)){milestones++;awarded+=xpPerCollectionMilestone;}
            }
            String quest=switch(skill){case "mining"->"mining";case "farming"->"farming";case "foraging"->"foraging";default->null;};
            if(quest!=null&&dailyTargets.getOrDefault(quest,0L)>0)try(PreparedStatement p=connection.prepareStatement("INSERT INTO daily_quest_progress(uuid,day_key,quest_id,progress,claimed) VALUES(?,?,?,1,0) ON CONFLICT(uuid,day_key,quest_id) DO UPDATE SET progress=MIN(?,progress+1)")){
                p.setString(1,id.toString());p.setString(2,questDay);p.setString(3,quest);p.setLong(4,dailyTargets.get(quest));p.executeUpdate();
            }
            connection.commit();return new GatherResult(skillBefore,skillAfter,milestones,awarded,newCollection);
        }catch(SQLException|RuntimeException e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
    }
    private boolean claimAndAward(UUID id,String key,long amount)throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("INSERT OR IGNORE INTO progression_claims(uuid,claim_key,created_at) VALUES(?,?,?)")){
            p.setString(1,id.toString());p.setString(2,key);p.setLong(3,System.currentTimeMillis());if(p.executeUpdate()!=1)return false;
        }
        if(amount<=0)return true;
        try(PreparedStatement p=connection.prepareStatement("UPDATE profiles SET skyblock_xp=skyblock_xp+? WHERE uuid=? AND skyblock_xp<=?")){
            p.setLong(1,amount);p.setString(2,id.toString());p.setLong(3,Long.MAX_VALUE-amount);if(p.executeUpdate()!=1)throw new SQLException("SkyBlock XP limit reached");
        }
        return true;
    }
    synchronized boolean claimAchievement(UUID id,String key,long xp)throws SQLException {
        connection.setAutoCommit(false);try{boolean claimed=claimAndAward(id,"achievement:"+key,xp);connection.commit();return claimed;}catch(SQLException|RuntimeException e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
    }
    synchronized List<DailyTask> dailyTasks(UUID id,String day)throws SQLException{
        List<DailyTask> tasks=new ArrayList<>();try(PreparedStatement p=connection.prepareStatement("SELECT quest_id,progress,claimed FROM daily_quest_progress WHERE uuid=? AND day_key=? ORDER BY quest_id")){p.setString(1,id.toString());p.setString(2,day);try(ResultSet r=p.executeQuery()){while(r.next())tasks.add(new DailyTask(r.getString(1),r.getLong(2),r.getInt(3)!=0));}}return tasks;
    }
    synchronized boolean claimDaily(UUID id,String day,String quest,long target,long coinMinor,long xp)throws SQLException{
        if(target<=0||coinMinor<0||xp<0)return false;connection.setAutoCommit(false);
        try{
            try(PreparedStatement p=connection.prepareStatement("INSERT OR IGNORE INTO daily_quest_progress(uuid,day_key,quest_id,progress,claimed) VALUES(?,?,?,0,0)")){p.setString(1,id.toString());p.setString(2,day);p.setString(3,quest);p.executeUpdate();}
            try(PreparedStatement p=connection.prepareStatement("UPDATE daily_quest_progress SET claimed=1 WHERE uuid=? AND day_key=? AND quest_id=? AND progress>=? AND claimed=0")){p.setString(1,id.toString());p.setString(2,day);p.setString(3,quest);p.setLong(4,target);if(p.executeUpdate()!=1){connection.rollback();return false;}}
            if(coinMinor>0){try(PreparedStatement p=connection.prepareStatement("UPDATE profiles SET balance_minor=balance_minor+?,total_earned_minor=total_earned_minor+? WHERE uuid=? AND balance_minor<=? AND total_earned_minor<=?")){p.setLong(1,coinMinor);p.setLong(2,coinMinor);p.setString(3,id.toString());p.setLong(4,Long.MAX_VALUE-coinMinor);p.setLong(5,Long.MAX_VALUE-coinMinor);if(p.executeUpdate()!=1)throw new SQLException("Coin balance limit reached");}ledger(id,coinMinor,"Daily quest "+quest);}
            if(!claimAndAward(id,"daily:"+day+":"+quest,xp))throw new SQLException("Daily XP claim already exists");
            connection.commit();return true;
        }catch(SQLException|RuntimeException e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
    }
    synchronized void markPlacedBlock(String world,int x,int y,int z,UUID owner,String material)throws SQLException{
        try(PreparedStatement p=connection.prepareStatement("INSERT OR REPLACE INTO placed_blocks(world,x,y,z,uuid,material) VALUES(?,?,?,?,?,?)")){p.setString(1,world);p.setInt(2,x);p.setInt(3,y);p.setInt(4,z);p.setString(5,owner.toString());p.setString(6,material);p.executeUpdate();}
    }
    synchronized boolean consumePlacedBlock(String world,int x,int y,int z)throws SQLException{
        try(PreparedStatement p=connection.prepareStatement("DELETE FROM placed_blocks WHERE world=? AND x=? AND y=? AND z=?")){p.setString(1,world);p.setInt(2,x);p.setInt(3,y);p.setInt(4,z);return p.executeUpdate()==1;}
    }

    synchronized MinionRow createMinion(String id, UUID owner, String islandId, String world,
                                        int x, int y, int z, String type, long now) throws SQLException {
        return createMinion(id, owner, islandId, world, x, y, z, type, 1, now);
    }
    synchronized MinionRow createMinion(String id, UUID owner, String islandId, String world,
                                        int x, int y, int z, String type, int tier, long now) throws SQLException {
        if (tier < 1 || tier > 11 || now < 0) throw new IllegalArgumentException("Invalid portable minion state");
        connection.setAutoCommit(false);try (PreparedStatement p=connection.prepareStatement("INSERT INTO minions(id,owner_uuid,island_id,world,x,y,z,type,tier,storage,last_action) VALUES(?,?,?,?,?,?,?,?,?,0,?)")) {
            p.setString(1,id); p.setString(2,owner.toString()); p.setString(3,islandId); p.setString(4,world);
            p.setInt(5,x); p.setInt(6,y); p.setInt(7,z); p.setString(8,type); p.setInt(9,tier); p.setLong(10,now); p.executeUpdate();
            connection.commit();
        }catch(SQLException|RuntimeException e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
        return minion(id);
    }
    synchronized boolean awardMinionType(UUID owner,String type)throws SQLException{
        connection.setAutoCommit(false);try{boolean awarded=claimAndAward(owner,"minion:type:"+type,25);connection.commit();return awarded;}
        catch(SQLException|RuntimeException e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
    }
    synchronized long bankBalance(UUID id) throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='bank_accounts'")) {
            if (!r.next()) return 0;
        }
        try (PreparedStatement p = connection.prepareStatement("SELECT balance_minor FROM bank_accounts WHERE profile_uuid=?")) {
            p.setString(1, id.toString()); try (ResultSet r = p.executeQuery()) { return r.next() ? r.getLong(1) : 0; }
        }
    }
    synchronized MinionRow minion(String id) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("SELECT id,owner_uuid,island_id,world,x,y,z,type,tier,storage,last_action FROM minions WHERE id=?")) {
            p.setString(1,id); try(ResultSet r=p.executeQuery()){return r.next()?row(r):null;}
        }
    }
    synchronized List<MinionRow> minionsForOwner(UUID owner) throws SQLException {
        List<MinionRow> rows=new ArrayList<>();
        try(PreparedStatement p=connection.prepareStatement("SELECT id,owner_uuid,island_id,world,x,y,z,type,tier,storage,last_action FROM minions WHERE owner_uuid=? ORDER BY last_action")) {
            p.setString(1,owner.toString()); try(ResultSet r=p.executeQuery()){while(r.next())rows.add(row(r));}
        }
        return rows;
    }
    synchronized List<MinionRow> minionsInChunk(String world,int chunkX,int chunkZ) throws SQLException {
        List<MinionRow> rows=new ArrayList<>();int minX=chunkX<<4,minZ=chunkZ<<4;
        try(PreparedStatement p=connection.prepareStatement("SELECT id,owner_uuid,island_id,world,x,y,z,type,tier,storage,last_action FROM minions WHERE world=? AND x>=? AND x<? AND z>=? AND z<?")) {
            p.setString(1,world);p.setInt(2,minX);p.setInt(3,minX+16);p.setInt(4,minZ);p.setInt(5,minZ+16);
            try(ResultSet r=p.executeQuery()){while(r.next())rows.add(row(r));}
        }
        return rows;
    }
    synchronized int countMinionsOnIsland(String islandId) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("SELECT COUNT(*) FROM minions WHERE island_id=?")) {
            p.setString(1,islandId); try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}
        }
    }
    synchronized int countMinionsForOwner(UUID owner)throws SQLException{try(PreparedStatement p=connection.prepareStatement("SELECT COUNT(*) FROM minions WHERE owner_uuid=?")){p.setString(1,owner.toString());try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}}}
    synchronized int countProgressionClaims(UUID owner)throws SQLException{try(PreparedStatement p=connection.prepareStatement("SELECT COUNT(*) FROM progression_claims WHERE uuid=?")){p.setString(1,owner.toString());try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}}}
    synchronized MinionRow advanceMinion(String id, long now, long intervalMillis, long offlineCapMillis,
                                         int storageCapacity) throws SQLException {
        connection.setAutoCommit(false);
        try {
            MinionRow old;
            try(PreparedStatement p=connection.prepareStatement("SELECT id,owner_uuid,island_id,world,x,y,z,type,tier,storage,last_action FROM minions WHERE id=?")) {
                p.setString(1,id); try(ResultSet r=p.executeQuery()){if(!r.next()){connection.rollback();return null;}old=row(r);}
            }
            MinionMath.Advance a=MinionMath.advance(old.storage(),old.lastAction(),now,intervalMillis,offlineCapMillis,storageCapacity);
            try(PreparedStatement p=connection.prepareStatement("UPDATE minions SET storage=?,last_action=? WHERE id=?")) {
                p.setInt(1,a.storage());p.setLong(2,a.lastAction());p.setString(3,id);p.executeUpdate();
            }
            connection.commit();
            return new MinionRow(old.id(),old.owner(),old.islandId(),old.world(),old.x(),old.y(),old.z(),old.type(),old.tier(),a.storage(),a.lastAction());
        } catch(SQLException|RuntimeException e){connection.rollback();throw e;} finally{connection.setAutoCommit(true);}
    }
    synchronized int takeMinionStorage(String id, UUID owner, int requested) throws SQLException {
        return takeMinionStorage(id,owner,requested,UUID.randomUUID().toString());
    }
    synchronized int takeMinionStorage(String id, UUID owner, int requested, String operation) throws SQLException {
        if(requested<=0)return 0;
        connection.setAutoCommit(false);
        try {
            int count;
            try(PreparedStatement p=connection.prepareStatement("SELECT storage FROM minions WHERE id=? AND owner_uuid=?")) {
                p.setString(1,id);p.setString(2,owner.toString());try(ResultSet r=p.executeQuery()){if(!r.next()){connection.rollback();return 0;}if(r.getInt(1)<requested){connection.rollback();return 0;}count=requested;}
            }
            try(PreparedStatement p=connection.prepareStatement("UPDATE minions SET storage=storage-? WHERE id=? AND owner_uuid=? AND storage>=?")) {
                p.setInt(1,count);p.setString(2,id);p.setString(3,owner.toString());p.setInt(4,count);if(p.executeUpdate()!=1){connection.rollback();return 0;}
            }
            minionReceipt(operation,owner,id,count);connection.commit();return count;
        } catch(SQLException|RuntimeException e){connection.rollback();throw e;} finally{connection.setAutoCommit(true);}
    }
    synchronized void returnMinionStorage(String id,int amount,int capacity) throws SQLException {
        if(amount<=0)return;
        try(PreparedStatement p=connection.prepareStatement("UPDATE minions SET storage=MIN(?,storage+?) WHERE id=?")) {
            p.setInt(1,capacity);p.setInt(2,amount);p.setString(3,id);p.executeUpdate();
        }
    }
    synchronized boolean upgradeMinion(String id,UUID owner,long cost) throws SQLException {
        if(cost<0)return false;connection.setAutoCommit(false);
        try {
            try(PreparedStatement p=connection.prepareStatement("UPDATE profiles SET balance_minor=balance_minor-? WHERE uuid=? AND balance_minor>=?")) {
                p.setLong(1,cost);p.setString(2,owner.toString());p.setLong(3,cost);if(p.executeUpdate()!=1){connection.rollback();return false;}
            }
            try(PreparedStatement p=connection.prepareStatement("UPDATE minions SET tier=tier+1 WHERE id=? AND owner_uuid=? AND tier<11")) {
                p.setString(1,id);p.setString(2,owner.toString());if(p.executeUpdate()!=1){connection.rollback();return false;}
            }
            ledger(owner,-cost,"Minion upgrade "+id);claimAndAward(owner,"minion:tier:"+rowTier(id),25);connection.commit();return true;
        }catch(SQLException|RuntimeException e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
    }
    synchronized boolean deleteMinion(String id,UUID owner)throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("DELETE FROM minions WHERE id=? AND owner_uuid=?")) {
            p.setString(1,id);p.setString(2,owner.toString());return p.executeUpdate()==1;
        }
    }
    synchronized boolean pickupMinion(String id,UUID owner,String operation)throws SQLException {
        connection.setAutoCommit(false);
        try {
            try(var p=connection.prepareStatement("DELETE FROM minions WHERE id=? AND owner_uuid=? AND storage=0")){
                p.setString(1,id);p.setString(2,owner.toString());if(p.executeUpdate()!=1){connection.rollback();return false;}
            }
            minionReceipt(operation,owner,id,0);connection.commit();return true;
        }catch(SQLException|RuntimeException e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
    }
    private void minionReceipt(String operation,UUID owner,String minion,int quantity)throws SQLException {
        try(var p=connection.prepareStatement("INSERT INTO minion_inventory_receipts(operation_key,owner_uuid,minion_id,quantity,created_at) VALUES(?,?,?,?,?)")) {
            p.setString(1,operation);p.setString(2,owner.toString());p.setString(3,minion);p.setInt(4,quantity);p.setLong(5,System.currentTimeMillis());p.executeUpdate();
        }
    }
    private MinionRow row(ResultSet r)throws SQLException {
        return new MinionRow(r.getString(1),UUID.fromString(r.getString(2)),r.getString(3),r.getString(4),
                r.getInt(5),r.getInt(6),r.getInt(7),r.getString(8),r.getInt(9),r.getInt(10),r.getLong(11));
    }
    private int rowTier(String id)throws SQLException{try(PreparedStatement p=connection.prepareStatement("SELECT tier FROM minions WHERE id=?")){p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):1;}}}
    @Override public synchronized void close() throws SQLException { connection.close(); }
}
