package com.r3dzf.nerqo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only provider exposing exactly the verified staged update APK. */
public final class UpdateFileProvider extends ContentProvider {
    public boolean onCreate() {return true;}
    private File updateFile(Uri uri) throws FileNotFoundException {
        if(!"/update.apk".equals(uri.getPath())) throw new FileNotFoundException("Not an update");
        File file = new File(getContext().getCacheDir(),"nerqo-updates/Nerqo-update.apk");
        if(!file.isFile()) throw new FileNotFoundException("Update missing");
        return file;
    }
    @Override public String getType(Uri uri) {return "application/vnd.android.package-archive";}
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = updateFile(uri);
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
            cursor.addRow(new Object[]{"Nerqo-update.apk",file.length()});
            return cursor;
        } catch(FileNotFoundException e) {return null;}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if(!"r".equals(mode)) throw new FileNotFoundException("Read-only");
        return ParcelFileDescriptor.open(updateFile(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) {throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args) {throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) {throw new UnsupportedOperationException();}
}
