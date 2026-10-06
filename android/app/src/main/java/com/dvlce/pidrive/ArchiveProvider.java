package com.dvlce.pidrive;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

public final class ArchiveProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File file(Uri uri) throws FileNotFoundException {
        File base=new File(getContext().getCacheDir(),"opened");
        File f=new File(base,uri.getLastPathSegment()==null ? "" : uri.getLastPathSegment());
        try { if(!f.getCanonicalFile().getParentFile().equals(base.getCanonicalFile())||!f.isFile())throw new FileNotFoundException(); }
        catch(IOException e){throw new FileNotFoundException();}
        return f;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"r".equals(mode))throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        try {File f=file(uri); MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{f.getName(),f.length()});return c;}
        catch(Exception e){return null;}
    }
    @Override public String getType(Uri uri){String ext=android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString());String mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());return mime==null?"application/octet-stream":mime;}
    @Override public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
}
