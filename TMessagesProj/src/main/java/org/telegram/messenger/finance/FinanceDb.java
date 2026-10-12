package org.telegram.messenger.finance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FORK MOD (Finance): the SQLite store. Rows are never hard-deleted ({@code del} flag + {@code updated} timestamp)
 * so backups from several devices can be merged "newest record wins".
 */
public class FinanceDb extends SQLiteOpenHelper {
    private static final String NAME = "finance.db";
    private static final int VERSION = 5;

    public static final String[] TABLES = {"entity", "account", "category", "tx", "asset", "asset_value", "debt", "debt_pay", "rate", "snapshot", "rule", "catrule"};

    public FinanceDb(Context ctx) {
        super(ctx, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entity(id TEXT PRIMARY KEY, name TEXT, emoji TEXT, color TEXT, sort INTEGER DEFAULT 0, archived INTEGER DEFAULT 0, del INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE account(id TEXT PRIMARY KEY, entity TEXT, name TEXT, emoji TEXT, currency TEXT, type INTEGER DEFAULT 0, initial REAL DEFAULT 0, sort INTEGER DEFAULT 0, archived INTEGER DEFAULT 0, wealth INTEGER DEFAULT 1, del INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE category(id TEXT PRIMARY KEY, kind INTEGER DEFAULT 0, name TEXT, emoji TEXT, color TEXT, entity TEXT, sort INTEGER DEFAULT 0, archived INTEGER DEFAULT 0, del INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE tx(id TEXT PRIMARY KEY, kind INTEGER DEFAULT 0, amount REAL DEFAULT 0, currency TEXT, account TEXT, to_account TEXT, to_amount REAL DEFAULT 0, category TEXT, entity TEXT, ts INTEGER DEFAULT 0, day INTEGER DEFAULT 0, note TEXT, source INTEGER DEFAULT 0, ext_id TEXT, del INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX tx_day ON tx(day)");
        db.execSQL("CREATE INDEX tx_account ON tx(account)");
        db.execSQL("CREATE INDEX tx_ext ON tx(ext_id)");
        db.execSQL("CREATE TABLE asset(id TEXT PRIMARY KEY, entity TEXT, name TEXT, emoji TEXT, cls INTEGER DEFAULT 0, code TEXT, qty REAL DEFAULT 0, currency TEXT, note TEXT, sort INTEGER DEFAULT 0, archived INTEGER DEFAULT 0, del INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE asset_value(asset TEXT, day INTEGER, value REAL DEFAULT 0, del INTEGER DEFAULT 0, updated INTEGER DEFAULT 0, PRIMARY KEY(asset, day))");
        db.execSQL("CREATE TABLE debt(id TEXT PRIMARY KEY, dir INTEGER DEFAULT 0, name TEXT, user_id INTEGER DEFAULT 0, amount REAL DEFAULT 0, currency TEXT, day INTEGER DEFAULT 0, due INTEGER DEFAULT 0, note TEXT, entity TEXT, closed INTEGER DEFAULT 0, del INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE debt_pay(id TEXT PRIMARY KEY, debt TEXT, amount REAL DEFAULT 0, day INTEGER DEFAULT 0, note TEXT, del INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE rate(code TEXT PRIMARY KEY, uzs REAL DEFAULT 0, updated INTEGER DEFAULT 0, manual INTEGER DEFAULT 0, src TEXT, name TEXT)");
        db.execSQL("CREATE TABLE snapshot(day INTEGER PRIMARY KEY, uzs REAL DEFAULT 0, json TEXT, updated INTEGER DEFAULT 0)");
        createV2(db);
        createV3(db);
        createV4(db);
        createV5(db);
    }

    private void createV2(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS rule(id TEXT PRIMARY KEY, dialog INTEGER DEFAULT 0, name TEXT, enabled INTEGER DEFAULT 1, mapping TEXT, account TEXT, cat_in TEXT, cat_out TEXT, auto INTEGER DEFAULT 0, cards TEXT, sample TEXT, del INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS inbox(id TEXT PRIMARY KEY, rule TEXT, dialog INTEGER DEFAULT 0, msg INTEGER DEFAULT 0, text TEXT, parsed TEXT, status INTEGER DEFAULT 0, created INTEGER DEFAULT 0, del INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS inbox_msg ON inbox(dialog, msg)");
    }

    private void createV3(SQLiteDatabase db) {
        try { db.execSQL("ALTER TABLE asset ADD COLUMN grp TEXT"); } catch (Exception ignore) {}
    }

    private void createV5(SQLiteDatabase db) {
        try { db.execSQL("ALTER TABLE entity ADD COLUMN currency TEXT"); } catch (Exception ignore) {}
    }

    private void createV4(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS catrule(id TEXT PRIMARY KEY, rule TEXT, pattern TEXT, category TEXT, account TEXT, auto INTEGER DEFAULT 0, del INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createV2(db);
        if (oldVersion < 3) createV3(db);
        if (oldVersion < 4) createV4(db);
        if (oldVersion < 5) createV5(db);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // region Seed

    /** First run: "Personal" entity, two UZS accounts and a sensible category set */
    public synchronized void seedDefaults() {
        if (!entities(true).isEmpty()) return;
        Entity p = new Entity();
        p.id = Entity.PERSONAL; p.name = "Personal"; p.emoji = "🙂"; p.color = "blue";
        saveEntity(p);
        Account cash = new Account();
        cash.id = Finance.newId(); cash.entity = Entity.PERSONAL; cash.name = "Cash"; cash.emoji = "💵"; cash.currency = "UZS"; cash.type = Account.TYPE_CASH;
        saveAccount(cash);
        Account card = new Account();
        card.id = Finance.newId(); card.entity = Entity.PERSONAL; card.name = "Card"; card.emoji = "💳"; card.currency = "UZS"; card.type = Account.TYPE_CARD; card.sort = 1;
        saveAccount(card);
        String[][] expense = {{"Food", "🍔", "#F97316"}, {"Groceries", "🛒", "#22C55E"}, {"Transport", "🚕", "#F59E0B"}, {"Home", "🏠", "#3B82F6"}, {"Bills", "🧾", "#64748B"},
            {"Shopping", "🛍️", "#EC4899"}, {"Health", "💊", "#EF4444"}, {"Family", "👨‍👩‍👧", "#8B5CF6"}, {"Education", "📚", "#3B82F6"}, {"Entertainment", "🎉", "#14B8A6"},
            {"Gifts", "🎁", "#EC4899"}, {"Charity", "🤲", "#22C55E"}, {"Other", "📦", "#64748B"}};
        String[][] income = {{"Salary", "💼", "#22C55E"}, {"Business", "🏪", "#3B82F6"}, {"Gift", "🎁", "#EC4899"}, {"Investment", "📈", "#14B8A6"}, {"Other", "📦", "#64748B"}};
        int sort = 0;
        for (String[] e : expense) saveCategory(newCategory(Category.EXPENSE, e[0], e[1], e[2], sort++));
        sort = 0;
        for (String[] e : income) saveCategory(newCategory(Category.INCOME, e[0], e[1], e[2], sort++));
    }

    private static Category newCategory(int kind, String name, String emoji, String color, int sort) {
        Category c = new Category();
        c.id = Finance.newId(); c.kind = kind; c.name = name; c.emoji = emoji; c.color = color; c.sort = sort;
        return c;
    }

    // endregion

    // region Entities

    public synchronized List<Entity> entities(boolean includeArchived) {
        List<Entity> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM entity WHERE del=0" + (includeArchived ? "" : " AND archived=0") + " ORDER BY sort, name", null)) {
            while (c.moveToNext()) out.add(readEntity(c));
        }
        return out;
    }

    public synchronized Entity entity(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM entity WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? readEntity(c) : null;
        }
    }

    private Entity readEntity(Cursor c) {
        Entity e = new Entity();
        e.id = str(c, "id"); e.name = nz(str(c, "name")); e.emoji = nz(str(c, "emoji")); e.color = nz(str(c, "color"));
        e.sort = c.getInt(c.getColumnIndexOrThrow("sort")); e.archived = c.getInt(c.getColumnIndexOrThrow("archived")) != 0;
        e.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
        e.currency = nz(str(c, "currency"));
        return e;
    }

    public synchronized void saveEntity(Entity e) {
        ContentValues v = new ContentValues();
        v.put("id", e.id); v.put("name", e.name); v.put("emoji", e.emoji); v.put("color", e.color); v.put("sort", e.sort);
        v.put("currency", e.currency); v.put("archived", e.archived ? 1 : 0); v.put("del", 0); v.put("updated", now());
        getWritableDatabase().insertWithOnConflict("entity", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        Finance.notifyChanged();
    }

    public synchronized void deleteEntity(String id) {
        if (Entity.PERSONAL.equals(id)) return;
        softDelete("entity", id);
    }

    // endregion

    // region Accounts

    public synchronized List<Account> accounts(boolean includeArchived) {
        List<Account> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM account WHERE del=0" + (includeArchived ? "" : " AND archived=0") + " ORDER BY sort, name", null)) {
            while (c.moveToNext()) out.add(readAccount(c));
        }
        return out;
    }

    public synchronized List<Account> accounts(String entity, boolean includeArchived) {
        List<Account> out = new ArrayList<>();
        for (Account a : accounts(includeArchived)) if (entity == null || entity.equals(a.entity)) out.add(a);
        return out;
    }

    public synchronized Account account(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM account WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? readAccount(c) : null;
        }
    }

    private Account readAccount(Cursor c) {
        Account a = new Account();
        a.id = str(c, "id"); a.entity = nz(str(c, "entity")); a.name = nz(str(c, "name")); a.emoji = nz(str(c, "emoji")); a.currency = nz(str(c, "currency"));
        a.type = c.getInt(c.getColumnIndexOrThrow("type")); a.initial = c.getDouble(c.getColumnIndexOrThrow("initial"));
        a.sort = c.getInt(c.getColumnIndexOrThrow("sort")); a.archived = c.getInt(c.getColumnIndexOrThrow("archived")) != 0;
        a.wealth = c.getInt(c.getColumnIndexOrThrow("wealth")) != 0; a.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
        return a;
    }

    public synchronized void saveAccount(Account a) {
        ContentValues v = new ContentValues();
        v.put("id", a.id); v.put("entity", a.entity); v.put("name", a.name); v.put("emoji", a.emoji); v.put("currency", a.currency);
        v.put("type", a.type); v.put("initial", a.initial); v.put("sort", a.sort); v.put("archived", a.archived ? 1 : 0);
        v.put("wealth", a.wealth ? 1 : 0); v.put("del", 0); v.put("updated", now());
        getWritableDatabase().insertWithOnConflict("account", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        Finance.notifyChanged();
    }

    public synchronized void deleteAccount(String id) {
        softDelete("account", id);
    }

    public synchronized int txCount(String accountId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tx WHERE del=0 AND (account=? OR to_account=?)", new String[]{accountId, accountId})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    // endregion

    // region Categories

    /** Categories of a kind visible for an entity: shared ones plus the entity's own. {@code entity} null — all. */
    public synchronized List<Category> categories(int kind, String entity, boolean includeArchived) {
        List<Category> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM category WHERE del=0 AND kind=?" + (includeArchived ? "" : " AND archived=0") + " ORDER BY sort, name", new String[]{String.valueOf(kind)})) {
            while (c.moveToNext()) {
                Category cat = readCategory(c);
                if (entity == null || cat.entity == null || cat.entity.isEmpty() || cat.entity.equals(entity)) out.add(cat);
            }
        }
        return out;
    }

    public synchronized Category category(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM category WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? readCategory(c) : null;
        }
    }

    private Category readCategory(Cursor c) {
        Category cat = new Category();
        cat.id = str(c, "id"); cat.kind = c.getInt(c.getColumnIndexOrThrow("kind")); cat.name = nz(str(c, "name")); cat.emoji = nz(str(c, "emoji"));
        cat.color = nz(str(c, "color")); cat.entity = str(c, "entity"); cat.sort = c.getInt(c.getColumnIndexOrThrow("sort"));
        cat.archived = c.getInt(c.getColumnIndexOrThrow("archived")) != 0; cat.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
        return cat;
    }

    public synchronized void saveCategory(Category cat) {
        ContentValues v = new ContentValues();
        v.put("id", cat.id); v.put("kind", cat.kind); v.put("name", cat.name); v.put("emoji", cat.emoji); v.put("color", cat.color);
        v.put("entity", cat.entity == null || cat.entity.isEmpty() ? null : cat.entity); v.put("sort", cat.sort);
        v.put("archived", cat.archived ? 1 : 0); v.put("del", 0); v.put("updated", now());
        getWritableDatabase().insertWithOnConflict("category", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        Finance.notifyChanged();
    }

    public synchronized void deleteCategory(String id) {
        softDelete("category", id);
    }

    // endregion

    // region Transactions

    public static class Filter {
        public String entity, account, category, extId, query;
        public int kind = -1;
        public long dayFrom = Long.MIN_VALUE, dayTo = Long.MAX_VALUE;
        public int limit = 0;
    }

    public synchronized Tx tx(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM tx WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? readTx(c) : null;
        }
    }

    public synchronized boolean hasExt(String extId) {
        if (extId == null) return false;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM tx WHERE ext_id=? AND del=0", new String[]{extId})) {
            return c.moveToFirst();
        }
    }

    public synchronized List<Tx> txs(Filter f) {
        StringBuilder sql = new StringBuilder("SELECT * FROM tx WHERE del=0");
        List<String> args = new ArrayList<>();
        if (f.entity != null) { sql.append(" AND entity=?"); args.add(f.entity); }
        if (f.account != null) { sql.append(" AND (account=? OR to_account=?)"); args.add(f.account); args.add(f.account); }
        if (f.category != null) { sql.append(" AND category=?"); args.add(f.category); }
        if (f.kind >= 0) { sql.append(" AND kind=?"); args.add(String.valueOf(f.kind)); }
        if (f.dayFrom != Long.MIN_VALUE) { sql.append(" AND day>=?"); args.add(String.valueOf(f.dayFrom)); }
        if (f.dayTo != Long.MAX_VALUE) { sql.append(" AND day<=?"); args.add(String.valueOf(f.dayTo)); }
        if (f.query != null && !f.query.isEmpty()) { sql.append(" AND note LIKE ?"); args.add("%" + f.query + "%"); }
        sql.append(" ORDER BY day DESC, ts DESC");
        if (f.limit > 0) sql.append(" LIMIT ").append(f.limit);
        List<Tx> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) out.add(readTx(c));
        }
        return out;
    }

    private Tx readTx(Cursor c) {
        Tx t = new Tx();
        t.id = str(c, "id"); t.kind = c.getInt(c.getColumnIndexOrThrow("kind")); t.amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
        t.currency = nz(str(c, "currency")); t.account = str(c, "account"); t.toAccount = str(c, "to_account");
        t.toAmount = c.getDouble(c.getColumnIndexOrThrow("to_amount")); t.category = str(c, "category"); t.entity = nz(str(c, "entity"));
        t.ts = c.getLong(c.getColumnIndexOrThrow("ts")); t.day = c.getLong(c.getColumnIndexOrThrow("day")); t.note = nz(str(c, "note"));
        t.source = c.getInt(c.getColumnIndexOrThrow("source")); t.extId = str(c, "ext_id"); t.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
        return t;
    }

    public synchronized void saveTx(Tx t) {
        if (t.id == null) t.id = Finance.newId();
        if (t.ts == 0) t.setMoment(now());
        ContentValues v = new ContentValues();
        v.put("id", t.id); v.put("kind", t.kind); v.put("amount", t.amount); v.put("currency", t.currency); v.put("account", t.account);
        v.put("to_account", t.kind == Tx.TRANSFER ? t.toAccount : null); v.put("to_amount", t.kind == Tx.TRANSFER ? t.toAmount : 0);
        v.put("category", t.kind == Tx.TRANSFER ? null : t.category); v.put("entity", t.entity); v.put("ts", t.ts); v.put("day", t.day);
        v.put("note", t.note); v.put("source", t.source); v.put("ext_id", t.extId); v.put("del", 0); v.put("updated", now());
        getWritableDatabase().insertWithOnConflict("tx", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        Finance.notifyChanged();
    }

    public synchronized void deleteTx(String id) {
        softDelete("tx", id);
    }

    // endregion

    // region Aggregates

    /** Current balance of every account (own currency), including archived ones */
    public synchronized Map<String, Double> balances() {
        return balancesAt(Long.MAX_VALUE);
    }

    /** Balances at the end of {@code day} (epoch day) */
    public synchronized Map<String, Double> balancesAt(long day) {
        HashMap<String, Double> out = new HashMap<>();
        for (Account a : accounts(true)) out.put(a.id, a.initial);
        String where = day == Long.MAX_VALUE ? "" : " AND day<=" + day;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT account, SUM(CASE kind WHEN 1 THEN amount ELSE -amount END) FROM tx WHERE del=0" + where + " GROUP BY account", null)) {
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (id != null && out.containsKey(id)) out.put(id, out.get(id) + c.getDouble(1));
            }
        }
        try (Cursor c = db.rawQuery("SELECT to_account, SUM(to_amount) FROM tx WHERE del=0 AND kind=2" + where + " GROUP BY to_account", null)) {
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (id != null && out.containsKey(id)) out.put(id, out.get(id) + c.getDouble(1));
            }
        }
        return out;
    }

    public static class CatSum {
        public String category;
        public String currency;
        public double amount;
        /** converted to the base currency */
        public double base;
        public int count;
    }

    /** Sums of expenses or incomes per category in a day range (grouped by currency too), {@code entity} null — all */
    public synchronized List<CatSum> sumByCategory(int kind, long dayFrom, long dayTo, String entity) {
        List<CatSum> out = new ArrayList<>();
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(kind)); args.add(String.valueOf(dayFrom)); args.add(String.valueOf(dayTo));
        String ent = "";
        if (entity != null) { ent = " AND entity=?"; args.add(entity); }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT category, currency, SUM(amount), COUNT(*) FROM tx WHERE del=0 AND kind=? AND day>=? AND day<=?" + ent + " GROUP BY category, currency", args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                CatSum s = new CatSum();
                s.category = c.getString(0); s.currency = nz(c.getString(1)); s.amount = c.getDouble(2); s.count = c.getInt(3);
                s.base = Rates.convertOr0(s.amount, s.currency, Finance.baseFor(entity));
                out.add(s);
            }
        }
        return out;
    }

    /** Total of a kind in the base currency */
    public synchronized double sumKind(int kind, long dayFrom, long dayTo, String entity) {
        double total = 0;
        for (CatSum s : sumByCategory(kind, dayFrom, dayTo, entity)) total += s.base;
        return total;
    }

    /** Money moved into / out of an entity from other entities' accounts (capital in, withdrawals), in the base currency: {in, out} */
    public synchronized double[] entityFlows(String entity, long dayFrom, long dayTo) {
        Map<String, Account> acc = new HashMap<>();
        for (Account a : accounts(true)) acc.put(a.id, a);
        double in = 0, out = 0;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT account, to_account, amount, currency, to_amount FROM tx WHERE del=0 AND kind=2 AND day>=? AND day<=?", new String[]{String.valueOf(dayFrom), String.valueOf(dayTo)})) {
            while (c.moveToNext()) {
                Account from = acc.get(c.getString(0)), to = acc.get(c.getString(1));
                if (from == null || to == null || from.entity.equals(to.entity)) continue;
                if (to.entity.equals(entity)) in += Rates.convertOr0(c.getDouble(4), to.currency, Finance.baseFor(entity));
                if (from.entity.equals(entity)) out += Rates.convertOr0(c.getDouble(2), nz(c.getString(3)), Finance.baseFor(entity));
            }
        }
        return new double[]{in, out};
    }

    // endregion

    // region Assets

    public synchronized List<Asset> assets(boolean includeArchived) {
        List<Asset> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM asset WHERE del=0" + (includeArchived ? "" : " AND archived=0") + " ORDER BY sort, name", null)) {
            while (c.moveToNext()) out.add(readAsset(c));
        }
        return out;
    }

    public synchronized Asset asset(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM asset WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? readAsset(c) : null;
        }
    }

    private Asset readAsset(Cursor c) {
        Asset a = new Asset();
        a.id = str(c, "id"); a.entity = nz(str(c, "entity")); a.name = nz(str(c, "name")); a.emoji = nz(str(c, "emoji"));
        a.cls = c.getInt(c.getColumnIndexOrThrow("cls")); a.code = str(c, "code"); a.qty = c.getDouble(c.getColumnIndexOrThrow("qty"));
        a.currency = nz(str(c, "currency")); a.note = nz(str(c, "note")); a.sort = c.getInt(c.getColumnIndexOrThrow("sort"));
        String grp = str(c, "grp"); a.group = grp == null || grp.isEmpty() ? (a.cls == Asset.CLS_QUOTED ? Asset.groupForCode(a.code) : "other") : grp;
        a.archived = c.getInt(c.getColumnIndexOrThrow("archived")) != 0; a.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
        return a;
    }

    public synchronized void saveAsset(Asset a) {
        if (a.id == null) a.id = Finance.newId();
        ContentValues v = new ContentValues();
        v.put("id", a.id); v.put("entity", a.entity); v.put("name", a.name); v.put("emoji", a.emoji); v.put("cls", a.cls); v.put("code", a.code);
        v.put("qty", a.qty); v.put("currency", a.currency); v.put("note", a.note); v.put("sort", a.sort); v.put("archived", a.archived ? 1 : 0);
        v.put("grp", a.group); v.put("del", 0); v.put("updated", now());
        getWritableDatabase().insertWithOnConflict("asset", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        Finance.notifyChanged();
    }

    public synchronized void deleteAsset(String id) {
        softDelete("asset", id);
    }

    /** Manual valuation for a day (replaces the same day) */
    public synchronized void saveAssetValue(String assetId, long day, double value) {
        ContentValues v = new ContentValues();
        v.put("asset", assetId); v.put("day", day); v.put("value", value); v.put("del", 0); v.put("updated", now());
        getWritableDatabase().insertWithOnConflict("asset_value", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        Finance.notifyChanged();
    }

    public synchronized void deleteAssetValue(String assetId, long day) {
        ContentValues v = new ContentValues();
        v.put("del", 1); v.put("updated", now());
        getWritableDatabase().update("asset_value", v, "asset=? AND day=?", new String[]{assetId, String.valueOf(day)});
        Finance.notifyChanged();
    }

    /** {day, value} pairs, oldest first */
    public synchronized List<double[]> assetValues(String assetId) {
        List<double[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT day, value FROM asset_value WHERE asset=? AND del=0 ORDER BY day", new String[]{assetId})) {
            while (c.moveToNext()) out.add(new double[]{c.getLong(0), c.getDouble(1)});
        }
        return out;
    }

    /** Latest manual value on or before {@code day}, NaN if none */
    public synchronized double assetValueAt(String assetId, long day) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM asset_value WHERE asset=? AND del=0 AND day<=? ORDER BY day DESC LIMIT 1", new String[]{assetId, String.valueOf(day)})) {
            return c.moveToFirst() ? c.getDouble(0) : Double.NaN;
        }
    }

    /** Rate codes used by quoted assets */
    public synchronized List<String> quotedCodes() {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT code FROM asset WHERE del=0 AND cls=1 AND code IS NOT NULL", null)) {
            while (c.moveToNext()) if (c.getString(0) != null && !c.getString(0).isEmpty()) out.add(c.getString(0));
        }
        return out;
    }

    // endregion

    // region Debts

    public synchronized List<Debt> debts(boolean includeClosed) {
        List<Debt> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT d.*, (SELECT IFNULL(SUM(p.amount),0) FROM debt_pay p WHERE p.debt=d.id AND p.del=0) AS paid FROM debt d WHERE d.del=0" + (includeClosed ? "" : " AND d.closed=0") + " ORDER BY d.closed, CASE WHEN d.due>0 THEN d.due ELSE 9999999 END, d.day DESC", null)) {
            while (c.moveToNext()) out.add(readDebt(c));
        }
        return out;
    }

    public synchronized Debt debt(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT d.*, (SELECT IFNULL(SUM(p.amount),0) FROM debt_pay p WHERE p.debt=d.id AND p.del=0) AS paid FROM debt d WHERE d.id=?", new String[]{id})) {
            return c.moveToFirst() ? readDebt(c) : null;
        }
    }

    private Debt readDebt(Cursor c) {
        Debt d = new Debt();
        d.id = str(c, "id"); d.dir = c.getInt(c.getColumnIndexOrThrow("dir")); d.name = nz(str(c, "name")); d.userId = c.getLong(c.getColumnIndexOrThrow("user_id"));
        d.amount = c.getDouble(c.getColumnIndexOrThrow("amount")); d.currency = nz(str(c, "currency")); d.day = c.getLong(c.getColumnIndexOrThrow("day"));
        d.due = c.getLong(c.getColumnIndexOrThrow("due")); d.note = nz(str(c, "note")); d.entity = nz(str(c, "entity"));
        d.closed = c.getInt(c.getColumnIndexOrThrow("closed")) != 0; d.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
        int pi = c.getColumnIndex("paid");
        d.paid = pi >= 0 ? c.getDouble(pi) : 0;
        return d;
    }

    public synchronized void saveDebt(Debt d) {
        if (d.id == null) d.id = Finance.newId();
        ContentValues v = new ContentValues();
        v.put("id", d.id); v.put("dir", d.dir); v.put("name", d.name); v.put("user_id", d.userId); v.put("amount", d.amount); v.put("currency", d.currency);
        v.put("day", d.day); v.put("due", d.due); v.put("note", d.note); v.put("entity", d.entity); v.put("closed", d.closed ? 1 : 0); v.put("del", 0); v.put("updated", now());
        getWritableDatabase().insertWithOnConflict("debt", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        Finance.notifyChanged();
    }

    public synchronized void deleteDebt(String id) {
        softDelete("debt", id);
    }

    public synchronized void addDebtPayment(String debtId, double amount, long day, String note) {
        ContentValues v = new ContentValues();
        v.put("id", Finance.newId()); v.put("debt", debtId); v.put("amount", amount); v.put("day", day); v.put("note", note); v.put("del", 0); v.put("updated", now());
        getWritableDatabase().insertWithOnConflict("debt_pay", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        Finance.notifyChanged();
    }

    public synchronized void deleteDebtPayment(String id) {
        softDelete("debt_pay", id);
    }

    /** {id, amount, day, note} rows of a debt, newest first */
    public synchronized List<Object[]> debtPayments(String debtId) {
        List<Object[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, amount, day, note FROM debt_pay WHERE debt=? AND del=0 ORDER BY day DESC", new String[]{debtId})) {
            while (c.moveToNext()) out.add(new Object[]{c.getString(0), c.getDouble(1), c.getLong(2), nz(c.getString(3))});
        }
        return out;
    }

    // endregion

    // region Rates

    public synchronized Map<String, Double> loadRates() {
        HashMap<String, Double> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT code, uzs, name FROM rate", null)) {
            while (c.moveToNext()) {
                out.put(c.getString(0), c.getDouble(1));
                if (c.getString(2) != null) Currencies.register(c.getString(0), c.getString(2));
            }
        }
        return out;
    }

    public synchronized double loadRate(String code) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT uzs FROM rate WHERE code=?", new String[]{code})) {
            return c.moveToFirst() ? c.getDouble(0) : Double.NaN;
        }
    }

    public synchronized String rateSource(String code) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT src FROM rate WHERE code=?", new String[]{code})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    /** Automatic saves never overwrite a manual rate */
    public synchronized void saveRate(String code, double uzs, boolean manual, String src, String name) {
        SQLiteDatabase db = getWritableDatabase();
        if (!manual) {
            try (Cursor c = db.rawQuery("SELECT manual FROM rate WHERE code=?", new String[]{code})) {
                if (c.moveToFirst() && c.getInt(0) != 0) return;
            }
        }
        ContentValues v = new ContentValues();
        v.put("code", code); v.put("uzs", uzs); v.put("updated", now()); v.put("manual", manual ? 1 : 0); v.put("src", src);
        if (name != null) v.put("name", name);
        if (db.update("rate", v, "code=?", new String[]{code}) == 0) db.insert("rate", null, v);
    }

    public synchronized void setRateManual(String code, boolean manual) {
        ContentValues v = new ContentValues();
        v.put("manual", manual ? 1 : 0);
        getWritableDatabase().update("rate", v, "code=?", new String[]{code});
    }

    public static class RateRow {
        public String code, src, name;
        public double uzs;
        public long updated;
        public boolean manual;
    }

    public synchronized List<RateRow> rateRows() {
        List<RateRow> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT code, uzs, updated, manual, src, name FROM rate ORDER BY code", null)) {
            while (c.moveToNext()) {
                RateRow r = new RateRow();
                r.code = c.getString(0); r.uzs = c.getDouble(1); r.updated = c.getLong(2); r.manual = c.getInt(3) != 0; r.src = c.getString(4); r.name = c.getString(5);
                out.add(r);
            }
        }
        return out;
    }

    // endregion

    // region Snapshots

    public synchronized void saveSnapshot(long day, double uzs, String json) {
        ContentValues v = new ContentValues();
        v.put("day", day); v.put("uzs", uzs); v.put("json", json); v.put("updated", now());
        getWritableDatabase().insertWithOnConflict("snapshot", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** {day, uzs} oldest first, from {@code fromDay} */
    public synchronized List<double[]> snapshots(long fromDay) {
        List<double[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT day, uzs FROM snapshot WHERE day>=? ORDER BY day", new String[]{String.valueOf(fromDay)})) {
            while (c.moveToNext()) out.add(new double[]{c.getLong(0), c.getDouble(1)});
        }
        return out;
    }

    public synchronized double snapshotAt(long day) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT uzs FROM snapshot WHERE day<=? ORDER BY day DESC LIMIT 1", new String[]{String.valueOf(day)})) {
            return c.moveToFirst() ? c.getDouble(0) : Double.NaN;
        }
    }

    // endregion

    // region Import rules + inbox

    public synchronized List<ImportRule> rules() {
        List<ImportRule> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM rule WHERE del=0 ORDER BY name", null)) {
            while (c.moveToNext()) out.add(readRule(c));
        }
        return out;
    }

    public synchronized ImportRule rule(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM rule WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? readRule(c) : null;
        }
    }

    public synchronized ImportRule ruleForDialog(long dialogId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM rule WHERE del=0 AND dialog=?", new String[]{String.valueOf(dialogId)})) {
            return c.moveToFirst() ? readRule(c) : null;
        }
    }

    private ImportRule readRule(Cursor c) {
        ImportRule r = new ImportRule();
        r.id = str(c, "id"); r.dialogId = c.getLong(c.getColumnIndexOrThrow("dialog")); r.name = nz(str(c, "name"));
        r.enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0; r.setMappingJson(str(c, "mapping")); r.account = str(c, "account");
        r.catIn = str(c, "cat_in"); r.catOut = str(c, "cat_out"); r.auto = c.getInt(c.getColumnIndexOrThrow("auto")) != 0;
        r.setCardsJson(str(c, "cards")); r.sample = nz(str(c, "sample")); r.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
        return r;
    }

    public synchronized void saveRule(ImportRule r) {
        if (r.id == null) r.id = Finance.newId();
        ContentValues v = new ContentValues();
        v.put("id", r.id); v.put("dialog", r.dialogId); v.put("name", r.name); v.put("enabled", r.enabled ? 1 : 0); v.put("mapping", r.mappingJson());
        v.put("account", r.account); v.put("cat_in", r.catIn); v.put("cat_out", r.catOut); v.put("auto", r.auto ? 1 : 0); v.put("cards", r.cardsJson());
        v.put("sample", r.sample); v.put("del", 0); v.put("updated", now());
        getWritableDatabase().insertWithOnConflict("rule", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        Finance.notifyChanged();
    }

    public synchronized void deleteRule(String id) {
        softDelete("rule", id);
    }

    public synchronized List<InboxItem> inbox(int status) {
        List<InboxItem> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM inbox WHERE del=0 AND status=? ORDER BY created DESC", new String[]{String.valueOf(status)})) {
            while (c.moveToNext()) out.add(readInbox(c));
        }
        return out;
    }

    public synchronized InboxItem inboxItem(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM inbox WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? readInbox(c) : null;
        }
    }

    public synchronized boolean hasInboxMessage(long dialogId, int msgId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM inbox WHERE dialog=? AND msg=? AND del=0", new String[]{String.valueOf(dialogId), String.valueOf(msgId)})) {
            return c.moveToFirst();
        }
    }

    private InboxItem readInbox(Cursor c) {
        InboxItem i = new InboxItem();
        i.id = str(c, "id"); i.rule = str(c, "rule"); i.dialogId = c.getLong(c.getColumnIndexOrThrow("dialog")); i.msgId = c.getInt(c.getColumnIndexOrThrow("msg"));
        i.text = nz(str(c, "text")); i.parsed = BotParser.Parsed.fromJson(str(c, "parsed")); i.status = c.getInt(c.getColumnIndexOrThrow("status"));
        i.created = c.getLong(c.getColumnIndexOrThrow("created"));
        return i;
    }

    public synchronized void saveInbox(InboxItem i) {
        if (i.id == null) i.id = Finance.newId();
        if (i.created == 0) i.created = now();
        ContentValues v = new ContentValues();
        v.put("id", i.id); v.put("rule", i.rule); v.put("dialog", i.dialogId); v.put("msg", i.msgId); v.put("text", i.text);
        v.put("parsed", i.parsed == null ? null : i.parsed.toJson()); v.put("status", i.status); v.put("created", i.created); v.put("del", 0); v.put("updated", now());
        getWritableDatabase().insertWithOnConflict("inbox", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        Finance.notifyChanged();
    }

    public synchronized void setInboxStatus(String id, int status) {
        ContentValues v = new ContentValues();
        v.put("status", status); v.put("updated", now());
        getWritableDatabase().update("inbox", v, "id=?", new String[]{id});
        Finance.notifyChanged();
    }

    public synchronized int pendingInboxCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM inbox WHERE del=0 AND status=0", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // endregion

    // region Category rules (learned merchant → category)

    public synchronized List<CatRule> catRules(String ruleId) {
        List<CatRule> out = new ArrayList<>();
        String sql = "SELECT * FROM catrule WHERE del=0" + (ruleId == null ? "" : " AND (rule=? OR rule IS NULL)") + " ORDER BY updated DESC";
        try (Cursor c = getReadableDatabase().rawQuery(sql, ruleId == null ? null : new String[]{ruleId})) {
            while (c.moveToNext()) {
                CatRule r = new CatRule();
                r.id = str(c, "id"); r.rule = str(c, "rule"); r.pattern = nz(str(c, "pattern")); r.category = str(c, "category"); r.account = str(c, "account");
                r.auto = c.getInt(c.getColumnIndexOrThrow("auto")) != 0; r.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
                out.add(r);
            }
        }
        return out;
    }

    /** Longest matching pattern wins */
    public synchronized CatRule catRuleFor(String ruleId, String text) {
        CatRule best = null;
        for (CatRule r : catRules(ruleId)) {
            if (r.matches(text) && (best == null || r.pattern.length() > best.pattern.length())) best = r;
        }
        return best;
    }

    public synchronized void saveCatRule(CatRule r) {
        if (r.id == null) r.id = Finance.newId();
        // one rule per (chat, pattern)
        for (CatRule o : catRules(null)) {
            if (!o.id.equals(r.id) && o.pattern.equalsIgnoreCase(r.pattern) && ((o.rule == null && r.rule == null) || (o.rule != null && o.rule.equals(r.rule)))) softDelete("catrule", o.id);
        }
        ContentValues v = new ContentValues();
        v.put("id", r.id); v.put("rule", r.rule); v.put("pattern", r.pattern); v.put("category", r.category); v.put("account", r.account);
        v.put("auto", r.auto ? 1 : 0); v.put("del", 0); v.put("updated", now());
        getWritableDatabase().insertWithOnConflict("catrule", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        Finance.notifyChanged();
    }

    public synchronized void deleteCatRule(String id) {
        softDelete("catrule", id);
    }

    // endregion

    // region Generic (backup)

    private void softDelete(String table, String id) {
        ContentValues v = new ContentValues();
        v.put("del", 1); v.put("updated", now());
        getWritableDatabase().update(table, v, "id=?", new String[]{id});
        Finance.notifyChanged();
    }

    /** Every row of a table (deleted ones too — they carry tombstones) as JSON objects */
    public synchronized JSONArray dump(String table) {
        JSONArray arr = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM " + table, null)) {
            String[] cols = c.getColumnNames();
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                for (int i = 0; i < cols.length; i++) {
                    if (c.isNull(i)) continue;
                    switch (c.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER: o.put(cols[i], c.getLong(i)); break;
                        case Cursor.FIELD_TYPE_FLOAT: o.put(cols[i], c.getDouble(i)); break;
                        default: o.put(cols[i], c.getString(i));
                    }
                }
                arr.put(o);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return arr;
    }

    /** Merges rows: a row replaces the local one only if its {@code updated} is newer. Returns the number of rows applied. */
    public synchronized int merge(String table, JSONArray rows) {
        if (rows == null) return 0;
        String[] pk = "asset_value".equals(table) ? new String[]{"asset", "day"} : "rate".equals(table) ? new String[]{"code"} : "snapshot".equals(table) ? new String[]{"day"} : new String[]{"id"};
        SQLiteDatabase db = getWritableDatabase();
        int n = 0;
        db.beginTransaction();
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o == null) continue;
                String[] args = new String[pk.length];
                StringBuilder where = new StringBuilder();
                boolean ok = true;
                for (int k = 0; k < pk.length; k++) {
                    if (!o.has(pk[k])) { ok = false; break; }
                    args[k] = String.valueOf(o.opt(pk[k]));
                    where.append(k > 0 ? " AND " : "").append(pk[k]).append("=?");
                }
                if (!ok) continue;
                long incoming = o.optLong("updated", 0);
                long local = -1;
                try (Cursor c = db.rawQuery("SELECT updated FROM " + table + " WHERE " + where, args)) {
                    if (c.moveToFirst()) local = c.getLong(0);
                }
                if (local >= 0 && local >= incoming) continue;
                ContentValues v = new ContentValues();
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    Object val = o.opt(k);
                    if (val instanceof Integer) v.put(k, (Integer) val);
                    else if (val instanceof Long) v.put(k, (Long) val);
                    else if (val instanceof Double) v.put(k, (Double) val);
                    else if (val instanceof Boolean) v.put(k, (Boolean) val ? 1 : 0);
                    else if (val != null && val != JSONObject.NULL) v.put(k, String.valueOf(val));
                }
                db.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            db.endTransaction();
        }
        return n;
    }

    public synchronized int count(String table) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table + " WHERE del=0", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // endregion

    public static long today() {
        return LocalDate.now().toEpochDay();
    }
}
