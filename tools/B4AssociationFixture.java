import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.system.Os;
import android.system.StructStat;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public final class B4AssociationFixture {
    private static final String TITLE = "RM-B4-Association-QA-20261026";
    private static final File ROOT = new File("/data/user/0/app.zhendong.reamicro");

    private static ContentValues values(Cursor row) {
        ContentValues result = new ContentValues();
        for (int index = 0; index < row.getColumnCount(); index++) {
            String key = row.getColumnName(index);
            if ("id".equals(key)) continue;
            switch (row.getType(index)) {
                case Cursor.FIELD_TYPE_INTEGER: result.put(key, row.getLong(index)); break;
                case Cursor.FIELD_TYPE_FLOAT: result.put(key, row.getDouble(index)); break;
                case Cursor.FIELD_TYPE_STRING: result.put(key, row.getString(index)); break;
                case Cursor.FIELD_TYPE_NULL: result.putNull(key); break;
                default: throw new IllegalStateException("Unexpected fixture column " + key);
            }
        }
        return result;
    }

    private static void copy(File source, File target) throws Exception {
        if (source.isDirectory()) {
            if (!target.mkdir()) throw new IllegalStateException("Cannot create fixture directory");
            File[] children = source.listFiles();
            if (children == null) throw new IllegalStateException("Cannot read fixture directory");
            for (File child : children) copy(child, new File(target, child.getName()));
        } else {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        }
        StructStat stat = Os.stat(source.getPath());
        Os.chown(target.getPath(), stat.st_uid, stat.st_gid);
        Os.chmod(target.getPath(), stat.st_mode & 0777);
    }

    private static long insert(SQLiteDatabase db, String table, ContentValues values) {
        StringBuilder columns = new StringBuilder();
        StringBuilder slots = new StringBuilder();
        for (String key : values.keySet()) {
            if (columns.length() > 0) {
                columns.append(',');
                slots.append(',');
            }
            columns.append('"').append(key.replace("\"", "\"\"")).append('"');
            slots.append('?');
        }
        try (SQLiteStatement statement = db.compileStatement(
                "INSERT INTO \"" + table + "\" (" + columns + ") VALUES (" + slots + ")")) {
            int index = 1;
            for (String key : values.keySet()) {
                Object value = values.get(key);
                if (value == null) statement.bindNull(index);
                else if (value instanceof Float || value instanceof Double) {
                    statement.bindDouble(index, ((Number)value).doubleValue());
                } else if (value instanceof Number) {
                    statement.bindLong(index, ((Number)value).longValue());
                } else statement.bindString(index, value.toString());
                index++;
            }
            return statement.executeInsert();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"create".equals(args[0])) throw new IllegalArgumentException("create only");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                new File(ROOT, "databases/app.db").getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            try (Cursor existing = db.rawQuery("SELECT id FROM books WHERE title=?", new String[]{TITLE})) {
                if (existing.moveToFirst()) throw new IllegalStateException("Fixture already exists");
            }
            ContentValues book;
            String sourceUuid;
            long uid;
            try (Cursor source = db.rawQuery(
                    "SELECT * FROM books WHERE id=133 AND title='RM-B4-WebDAV-QA-20261025'", null)) {
                if (!source.moveToFirst()) throw new IllegalStateException("Task source missing");
                book = values(source);
                uid = source.getLong(source.getColumnIndexOrThrow("uid"));
                sourceUuid = source.getString(source.getColumnIndexOrThrow("uuid"));
            }
            String uuid = UUID.randomUUID().toString();
            File parent = new File(ROOT, "files/" + uid + "/books");
            File source = new File(parent, sourceUuid).getCanonicalFile();
            File target = new File(parent, uuid).getCanonicalFile();
            if (!source.isDirectory() || target.exists() ||
                    !target.getParentFile().equals(parent.getCanonicalFile())) {
                throw new IllegalStateException("Unsafe fixture paths");
            }
            copy(source, target);
            book.put("title", TITLE);
            book.put("uuid", uuid);
            book.put("uri", "fixture://reamicro/" + uuid);
            book.put("cloud_id", 43503L);
            book.put("publisher", "\u8d77\u70b9\u4e2d\u6587\u7f51");
            book.put("backup_type", 0);
            book.put("backup_id", "");
            book.put("backup_code", "");
            book.put("progress", 0.0);
            book.put("finished", 0L);
            book.put("created", System.currentTimeMillis());
            book.put("updated", System.currentTimeMillis());
            db.beginTransaction();
            long id;
            try {
                id = insert(db, "books", book);
                for (String table : new String[]{"book_item_ref", "book_chapter"}) {
                    try (Cursor rows = db.rawQuery("SELECT * FROM " + table + " WHERE book_id=133", null)) {
                        while (rows.moveToNext()) {
                            ContentValues child = values(rows);
                            child.put("book_id", id);
                            insert(db, table, child);
                        }
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            System.out.println("Created task-only association fixture id=" + id + " title=" + TITLE);
        }
    }
}
