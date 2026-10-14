package network.nexora.core.api;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/** Async journal/receipt protocol. Session methods run exclusively on the supplied main executor.
 * Business work runs exclusively on the database executor and must not access Bukkit objects.
 * The platform must reject player inventory interactions while busy(owner) is true. */
public final class InventoryTransferEngine {
    public interface Session {
        UUID owner();
        boolean active();
        byte[] snapshot();
        void save(byte[] inventory);
        void failClosed(Throwable failure);
    }
    private final InventoryJournal journal;
    private final Executor database;
    private final Executor main;
    private final Semaphore capacity;
    private final ConcurrentHashMap<UUID, Boolean> busy = new ConcurrentHashMap<>();

    public InventoryTransferEngine(InventoryJournal journal, Executor database, Executor main, int maxPending) {
        this.journal = Objects.requireNonNull(journal);
        this.database = Objects.requireNonNull(database);
        this.main = Objects.requireNonNull(main);
        if (maxPending < 1) throw new IllegalArgumentException("Invalid pending-operation limit");
        this.capacity = new Semaphore(maxPending);
    }
    public boolean busy(UUID owner) { return busy.containsKey(owner); }

    /** Call on the main executor. Both snapshots are copied before returning to the caller. */
    public <T> CompletableFuture<T> transfer(Session session, InventoryJournal.Domain domain,
                                             String operation, byte[] after, Callable<T> business) {
        Objects.requireNonNull(session); Objects.requireNonNull(domain); Objects.requireNonNull(business);
        UUID owner = Objects.requireNonNull(session.owner());
        if (!capacity.tryAcquire()) return CompletableFuture.failedFuture(new IllegalStateException("Inventory service is busy"));
        if (busy.putIfAbsent(owner, true) != null) {
            capacity.release();
            return CompletableFuture.failedFuture(new IllegalStateException("Your previous inventory operation is still finishing"));
        }
        var future = new CompletableFuture<T>();
        byte[] before;
        byte[] next;
        try { before = session.snapshot().clone(); next = Objects.requireNonNull(after).clone(); }
        catch (RuntimeException error) { complete(owner, future, null, error); return future; }
        try {
            database.execute(() -> {
                try { journal.prepare(owner, domain, operation, before, next); }
                catch (Exception error) { onMain(() -> complete(owner, future, null, error)); return; }
                onMain(() -> {
                    boolean apply;
                    try { apply = session.active() && Arrays.equals(before, session.snapshot()); }
                    catch (Exception error) { recover(session, owner, operation, future, null, error); return; }
                    if (!apply) {
                        // No inventory or business mutation has occurred. A stale plan can be discarded.
                        runDatabase(session, owner, future, () -> {
                            try { journal.finish(owner, operation); }
                            catch (Exception error) { onMain(() -> fatal(session, owner, future, error)); return; }
                            onMain(() -> complete(owner, future, null, new IllegalStateException("Inventory changed before the operation could start")));
                        });
                        return;
                    }
                    try { session.save(next); }
                    catch (Exception error) { recover(session, owner, operation, future, null, error); return; }
                    runDatabase(session, owner, future, () -> {
                        T result = null;
                        Exception failure = null;
                        try {
                            result = business.call();
                            if (domain == InventoryJournal.Domain.CRAFT) journal.commitCraft(owner, operation);
                        } catch (Exception error) { failure = error; }
                        recover(session, owner, operation, future, result, failure);
                    });
                });
            });
        } catch (RuntimeException error) { complete(owner, future, null, error); }
        return future;
    }

    private <T> void recover(Session session, UUID owner, String operation, CompletableFuture<T> future, T result, Exception failure) {
        runDatabase(session, owner, future, () -> {
            InventoryJournal.Pending pending;
            try {
                pending = journal.pending(owner);
                if (pending == null || !operation.equals(pending.operation())) throw new SQLException("Inventory recovery receipt mismatch");
            } catch (Exception error) {
                if (failure != null) error.addSuppressed(failure);
                onMain(() -> fatal(session, owner, future, error)); return;
            }
            onMain(() -> {
                if (!session.active()) {
                    // Keep the journal for the next login. Never save a stale disconnected Player.
                    complete(owner, future, result, failure); return;
                }
                try { session.save(pending.committed() ? pending.after() : pending.before()); }
                catch (Exception error) { fatal(session, owner, future, error); return; }
                runDatabase(session, owner, future, () -> {
                    try { journal.finish(owner, operation); }
                    catch (Exception error) { onMain(() -> fatal(session, owner, future, error)); return; }
                    onMain(() -> complete(owner, future, result, failure));
                });
            });
        });
    }

    private <T> void runDatabase(Session session, UUID owner, CompletableFuture<T> future, Runnable work) {
        try { database.execute(work); }
        catch (RuntimeException error) { onMain(() -> fatal(session, owner, future, error)); }
    }
    private void onMain(Runnable task) { main.execute(task); }
    private <T> void fatal(Session session, UUID owner, CompletableFuture<T> future, Throwable error) {
        try { session.failClosed(error); }
        finally { complete(owner, future, null, error); }
    }
    private <T> void complete(UUID owner, CompletableFuture<T> future, T result, Throwable failure) {
        if (busy.remove(owner) != null) capacity.release();
        if (failure == null) future.complete(result); else future.completeExceptionally(failure);
    }
}
