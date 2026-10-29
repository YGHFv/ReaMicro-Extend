import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** 仅回读本轮测试书，不打印真实账号凭证或其他图书内容。 */
public final class Rc3AcceptanceProbe {
    static String hash(File file) throws Exception {
        if (!file.isFile()) return "";
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
        StringBuilder result=new StringBuilder();
        for(byte b:digest) result.append(String.format("%02x",b&255));
        return result.toString();
    }
    public static void main(String[] args) throws Exception {
        if(args.length==1 && args[0].equals("--fixture-use-current-cloud")) {
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(
                    "/data/user/0/app.zhendong.reamicro/databases/app.db",null,SQLiteDatabase.OPEN_READWRITE)) {
                android.content.ContentValues values=new android.content.ContentValues();
                try(Cursor source=db.rawQuery("SELECT cloud_id,publisher FROM books WHERE id=26 AND cloud_id=40218",null)) {
                    if(!source.moveToFirst()) throw new IllegalStateException("Reference association changed");
                    values.put("cloud_id",source.getLong(0));
                    values.put("publisher",source.getString(1));
                }
                int changed=db.update("books",values,"id=? AND uuid=? AND title=? AND cloud_id=?",
                    new String[]{"58","f349b5cf-59a0-423f-be0d-e4b0bc5b3ece","RC3 Audit Fixture Association","43503"});
                if(changed!=1) throw new IllegalStateException("Task-only fixture identity differs");
                System.out.println("Only task fixture 58 now uses cloud reference 40218");
            }
            return;
        }
        if(args.length==1 && args[0].equals("--cloud-ids")) {
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(
                    "/data/user/0/app.zhendong.reamicro/databases/app.db",null,SQLiteDatabase.OPEN_READONLY);
                Cursor rows=db.rawQuery("SELECT id,cloud_id FROM books WHERE cloud_id>0 ORDER BY id",null)) {
                while(rows.moveToNext()) System.out.println(rows.getLong(0)+":"+rows.getLong(1));
            }
            return;
        }
        if(args.length!=0) throw new IllegalArgumentException("Read-only fixture evidence only");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(
                "/data/user/0/app.zhendong.reamicro/databases/app.db",null,SQLiteDatabase.OPEN_READONLY);
            Cursor rows=db.rawQuery("SELECT * FROM books WHERE title LIKE ? ORDER BY id",
                new String[]{"RC3%"})) {
            JSONArray result=new JSONArray();
            while(rows.moveToNext()) {
                JSONObject row=new JSONObject();
                for(int i=0;i<rows.getColumnCount();i++) {
                    String name=rows.getColumnName(i);
                    if(name.equals("uri")) continue;
                    switch(rows.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL: row.put(name,JSONObject.NULL);break;
                        case Cursor.FIELD_TYPE_INTEGER: row.put(name,rows.getLong(i));break;
                        case Cursor.FIELD_TYPE_FLOAT: row.put(name,rows.getDouble(i));break;
                        case Cursor.FIELD_TYPE_STRING: row.put(name,rows.getString(i));break;
                        default: throw new IllegalStateException("Unexpected field "+name);
                    }
                }
                File root=new File("/data/user/0/app.zhendong.reamicro/files/"
                    +row.getLong("uid")+"/books/"+row.getString("uuid"));
                row.put("containerExists",new File(root,"META-INF/container.xml").isFile());
                row.put("opfSha256",hash(new File(root,"OEBPS/content.opf")));
                row.put("contentSha256",hash(new File(root,"OEBPS/one.xhtml")));
                for(String table:new String[]{"book_item_ref","book_chapter","marks"}) {
                    try(Cursor count=db.rawQuery("SELECT COUNT(*) FROM "+table+" WHERE book_id=?",
                            new String[]{row.getString("id")})) {
                        if(count.moveToFirst()) row.put(table+"Count",count.getInt(0));
                    } catch(Exception e) { row.put(table+"CountError",e.getClass().getSimpleName()); }
                }
                result.put(row);
            }
            System.out.println(result.toString());
        }
    }
}
