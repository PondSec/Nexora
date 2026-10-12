package network.nexora.core.api;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Bounded, thread-safe execute/transaction latency measurements; no SQL or parameters retained. */
public final class JdbcMetrics {
    public enum Source { CORE, MARKET, PROGRESSION, INVENTORY }
    public record Snapshot(long calls,long failures,long serverThreadCalls,double meanMillis,double p95UpperMillis,double maxMillis) {}
    private static final EnumMap<Source,Counter> COUNTERS=new EnumMap<>(Source.class);
    private static volatile long serverThreadId=-1;
    static {for(var source:Source.values())COUNTERS.put(source,new Counter());}
    private JdbcMetrics() {}
    public static void setServerThreadId(long id){serverThreadId=id;}
    public static Map<Source,Snapshot> snapshots(){
        Map<Source,Snapshot> result=new EnumMap<>(Source.class);
        COUNTERS.forEach((source,c)->result.put(source,c.snapshot()));return Map.copyOf(result);
    }
    public static Connection instrument(Connection connection,Source source) {
        Objects.requireNonNull(connection);Objects.requireNonNull(source);
        return (Connection)Proxy.newProxyInstance(JdbcMetrics.class.getClassLoader(),new Class<?>[]{Connection.class},
                (proxy,method,args)->{
                    Object result=invoke(proxy,connection,method,args,source,Set.of("commit","rollback","setAutoCommit").contains(method.getName()));
                    if(result instanceof Statement statement){
                        Class<?> type=statement instanceof CallableStatement?CallableStatement.class:statement instanceof PreparedStatement?PreparedStatement.class:Statement.class;
                        return Proxy.newProxyInstance(JdbcMetrics.class.getClassLoader(),new Class<?>[]{type},(p,m,a)->invoke(p,statement,m,a,source,m.getName().startsWith("execute")));
                    }
                    return result;
                });
    }
    private static Object invoke(Object proxy,Object target,Method method,Object[] args,Source source,boolean measured)throws Throwable {
        if(method.getDeclaringClass()==Object.class)return switch(method.getName()) {
            case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);default->"Nexora instrumented "+target.getClass().getSimpleName();
        };
        long start=measured?System.nanoTime():0;boolean failed=false;
        try{return method.invoke(target,args);}
        catch(InvocationTargetException error){failed=true;throw error.getCause();}
        finally{if(measured)COUNTERS.get(source).record(System.nanoTime()-start,failed,Thread.currentThread().threadId()==serverThreadId);}
    }
    private static final class Counter {
        final LongAdder calls=new LongAdder(),failures=new LongAdder(),serverCalls=new LongAdder(),total=new LongAdder();
        final AtomicLong max=new AtomicLong();final AtomicLongArray buckets=new AtomicLongArray(64);
        void record(long nanos,boolean failed,boolean serverThread) {
            nanos=Math.max(1,nanos);calls.increment();if(failed)failures.increment();if(serverThread)serverCalls.increment();total.add(nanos);max.accumulateAndGet(nanos,Math::max);
            buckets.incrementAndGet(64-Long.numberOfLeadingZeros(nanos));
        }
        Snapshot snapshot() {
            long count=calls.sum(),wanted=(long)Math.ceil(count*0.95),sum=0;double percentile=0;
            for(int i=0;i<buckets.length()&&count>0;i++){sum+=buckets.get(i);if(sum>=wanted){percentile=Math.scalb(1D,i)/1_000_000D;break;}}
            return new Snapshot(count,failures.sum(),serverCalls.sum(),count==0?0:total.sum()/(double)count/1_000_000D,percentile,max.get()/1_000_000D);
        }
    }
}
