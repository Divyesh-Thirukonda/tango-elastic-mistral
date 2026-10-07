package com.tango.phone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Only a pending camera file is shareable, via a temporary per-URI Android grant. */
public final class AttachmentProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File file(Uri uri) throws FileNotFoundException {
        try{return new AttachmentStore(getContext().getFilesDir()).cameraFile(getContext(),uri);}
        catch(Exception error){throw new FileNotFoundException(error.getMessage());}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!mode.equals("w")&&!mode.equals("wt")&&!mode.equals("rw")&&!mode.equals("rwt")&&!mode.equals("wa")&&!mode.equals("r"))throw new FileNotFoundException("Unsupported camera mode.");
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String getType(Uri uri){return "image/jpeg";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        try{File file=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))row[i]="Photo.jpg";else if(OpenableColumns.SIZE.equals(columns[i]))row[i]=file.length();}cursor.addRow(row);return cursor;}
        catch(FileNotFoundException error){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
