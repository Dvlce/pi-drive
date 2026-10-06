package com.dvlce.pidrive;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final int INK=Color.rgb(41,37,36), GREEN=Color.rgb(40,92,69), BG=Color.rgb(253,252,248), SAGE=Color.rgb(232,239,232);
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout screen;
    private TextView connection,diskLabel,progressText;
    private ProgressBar progress;
    private Button resume,cleanup;
    private JSONArray disks=new JSONArray();
    private String selectedDisk="", selectedName="", archivePath="";
    private boolean home=true, refreshing=false, busy=false, destroyed=false;
    private ArrayList<JSONObject> pendingMedia=new ArrayList<>();
    private final Runnable tick=new Runnable(){@Override public void run(){if(destroyed)return;if(home){updateProgress();refreshDisks(false);}handler.postDelayed(this,8000);}};
    private final Runnable progressTick=new Runnable(){@Override public void run(){if(destroyed)return;if(home)updateProgress();handler.postDelayed(this,800);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        selectedDisk=getPreferences(0).getString("disk","");
        selectedName=getPreferences(0).getString("diskName","");
        showHome(); handler.post(tick);handler.post(progressTick);
        if(getSharedPreferences("connection",0).getString("token","").isEmpty()) setup();
    }
    @Override public void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);executor.shutdown();super.onDestroy();}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable background(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(20));return d;}
    private void frame(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);scroll.setFitsSystemWindows(true);
        screen=new LinearLayout(this);screen.setOrientation(LinearLayout.VERTICAL);screen.setPadding(dp(24),dp(20),dp(24),dp(32));
        scroll.addView(screen);setContentView(scroll);
    }
    private TextView text(String value,int size,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(INK);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setPadding(0,dp(8),0,dp(8));screen.addView(t);return t;
    }
    private Button button(String label,boolean primary,Runnable action){
        Button b=new Button(this);b.setText(label);b.setTextSize(20);b.setAllCaps(false);b.setMinHeight(dp(68));
        b.setPadding(dp(16),dp(12),dp(16),dp(12));b.setTextColor(primary?Color.WHITE:GREEN);b.setBackground(background(primary?GREEN:SAGE));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(8));screen.addView(b,p);
        b.setOnClickListener(v->action.run());return b;
    }
    private void notice(String title,String message){if(!isFinishing()&&!destroyed)new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Va bene",null).show();}
    private void failure(Exception e){busy=false;notice("Controlliamo insieme",TransferService.friendly(e));}
    private void task(Callable<?> action){
        if(busy){notice("Un momento","Sto completando l’operazione. Attendi qualche secondo.");return;}
        busy=true;
        executor.execute(()->{try{action.call();}catch(Exception e){runOnUiThread(()->failure(e));}finally{runOnUiThread(()->busy=false);}});
    }
    private void showHome(){
        home=true;archivePath="";frame();
        text("Pi Drive",16,true);
        text("I tuoi ricordi,\nal sicuro.",30,true);
        connection=text("Controllo del collegamento…",18,false);
        diskLabel=text(selectedName.isEmpty()?"Scegli dove salvare i tuoi file":selectedName,20,true);
        button("Scegli disco",false,this::chooseDisk);
        text("Cosa vuoi salvare?",20,true);
        button("Salva foto e video",true,this::photos);
        button("Scegli altri file",false,this::otherFiles);
        button("Vedi file salvati",false,()->browse(""));
        progressText=text(TransferService.message,18,false);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);screen.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
        resume=button("Riprendi salvataggio",false,()->{if(TransferService.running){TransferService.pause=true;resume.setEnabled(false);}else startTransfers();});
        cleanup=button("Libera spazio sul telefono",false,this::confirmCleanup);
        text("Rimuove solo i file già salvati e controllati sul disco.",16,false);
        button("Impostazioni",false,this::setup);
        updateProgress();refreshDisks(false);
    }
    private void updateProgress(){
        if(progressText==null)return;
        progressText.setText(TransferService.message+(TransferService.total>0?"\n"+Math.min(100,TransferService.done*100/TransferService.total)+"% · "+bytes(TransferService.done)+" di "+bytes(TransferService.total):""));
        progress.setProgress(TransferService.total==0?0:(int)Math.min(100,TransferService.done*100/TransferService.total));
        resume.setText(TransferService.running?(TransferService.pause?"Pausa in corso…":"Pausa"):"Riprendi salvataggio");
        resume.setEnabled(!TransferService.running||!TransferService.pause);
        cleanup.setEnabled(!TransferService.running&&!busy);
    }
    private void setup(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(8),dp(24),dp(8));
        TextView intro=new TextView(this);intro.setText("Configurazione iniziale: può farla un familiare.\nUsa lo stesso Wi-Fi del Raspberry, oppure attiva Tailscale.");intro.setTextSize(18);box.addView(intro);
        EditText address=new EditText(this);address.setSingleLine(true);address.setText(getSharedPreferences("connection",0).getString("address","http://100.69.174.33:8090"));address.setHint("Indirizzo Raspberry");address.setInputType(17);box.addView(address);
        EditText code=new EditText(this);code.setSingleLine(true);code.setHint("Codice privato dell’archivio");code.setText(getSharedPreferences("connection",0).getString("token",""));code.setInputType(129);box.addView(code);
        new AlertDialog.Builder(this).setTitle("Collega il tuo archivio").setView(box).setNegativeButton("Annulla",null).setPositiveButton("Salva",(d,w)->{
            if(TransferService.running){notice("Salvataggio in corso","Metti in pausa prima di cambiare archivio.");return;}
            String base=address.getText().toString().trim();if(!base.startsWith("http://")&&!base.startsWith("https://"))base="http://"+base;
            String secret=code.getText().toString().trim();if(secret.length()<16){notice("Codice non completo","Inserisci il codice privato fornito durante la configurazione.");return;}
            getSharedPreferences("connection",0).edit().putString("address",base).putString("token",secret).apply();
            showHome();
        }).show();
    }
    private void refreshDisks(boolean selectAfter){
        if(refreshing||destroyed)return;refreshing=true;
        executor.execute(()->{
            try{
                JSONArray result=new Api(this).json("GET","/api/disks",null).getJSONArray("disks");
                runOnUiThread(()->{disks=result;refreshing=false;if(home){connection.setText("Collegato al tuo archivio");connection.setTextColor(GREEN);diskLabel.setText(selectedName.isEmpty()?"Scegli dove salvare i tuoi file":selectedName+" · scollegato");
                    for(int i=0;i<disks.length();i++){JSONObject disk=disks.optJSONObject(i);if(disk!=null&&selectedDisk.equals(disk.optString("id")))diskLabel.setText(disk.optString("name")+(disk.isNull("free")?" · da collegare":"\n"+bytes(disk.optLong("free"))+" disponibili"));}}
                    if(selectAfter)diskDialog();});
            }catch(Exception e){runOnUiThread(()->{refreshing=false;if(home)connection.setText("Archivio non raggiungibile. Controlla Wi-Fi o Tailscale.");if(selectAfter)failure(e);});}
        });
    }
    private void chooseDisk(){if(TransferService.running){notice("Prima metti in pausa","I file in corso hanno già una destinazione. Metti in pausa per scegliere un altro disco.");return;}refreshDisks(true);}
    private void diskDialog(){
        if(disks.length()==0){notice("Nessun disco collegato","Collega l’hard disk al Raspberry e tocca Scegli disco di nuovo.");return;}
        String[] labels=new String[disks.length()];
        for(int i=0;i<disks.length();i++){JSONObject d=disks.optJSONObject(i);labels[i]=d.optString("name")+" · "+bytes(d.optLong("size"))+"\n"+(d.optBoolean("available")?(d.isNull("free")?"Tocca per collegare":bytes(d.optLong("free"))+" liberi"):d.optString("reason"));}
        new AlertDialog.Builder(this).setTitle("Dove vuoi salvare?").setItems(labels,(dialog,index)->{
            JSONObject d=disks.optJSONObject(index);
            if(!d.optBoolean("available")){notice("Questo disco è protetto",d.optString("reason"));return;}
            task(()->{new Api(this).json("POST","/api/mount",new JSONObject().put("disk",d.getString("id")));
                runOnUiThread(()->{selectedDisk=d.optString("id");selectedName=d.optString("name");getPreferences(0).edit().putString("disk",selectedDisk).putString("diskName",selectedName).apply();showHome();});return null;});
        }).setNegativeButton("Chiudi",null).show();
    }
    private boolean ready(){
        if(TransferService.running){notice("Salvataggio in corso","Metti in pausa prima di aggiungere altri file.");return false;}
        if(selectedDisk.isEmpty()){notice("Scegli prima il disco","Tocca Scegli disco e indica dove vuoi salvare i tuoi file.");return false;}
        return true;
    }
    private void photos(){
        if(!ready())return;
        new AlertDialog.Builder(this).setTitle("Quali ricordi vuoi salvare?").setItems(new String[]{"Tutte le foto e i video accessibili","Scegli alcune foto e video"},(d,w)->{if(w==0)galleryPermission();else picker("*/*",new String[]{"image/*","video/*"});}).setNegativeButton("Annulla",null).show();
    }
    private void galleryPermission(){
        String[] permissions=Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO}:new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        boolean allowed=true;for(String permission:permissions)if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)allowed=false;
        if(allowed)collectGallery();else requestPermissions(permissions,201);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==201){boolean any=false;for(int result:results)if(result==PackageManager.PERMISSION_GRANTED)any=true;
            if(Build.VERSION.SDK_INT>=34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED)any=true;
            if(any)collectGallery();else notice("Permesso non concesso","Puoi comunque scegliere singole foto e video dal pulsante Salva foto e video.");}
        if(request==202 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)exportContacts();
    }
    private String batch(){return "Telefono/"+new SimpleDateFormat("yyyy-MM-dd",Locale.ITALY).format(new Date());}
    private static String safeName(String name){String s=name.replaceAll("[\\\\/\\p{Cntrl}]","_").replaceAll("^\\.+","");if(s.isEmpty())s="file";if(s.length()>120)s=s.substring(0,120);return s;}
    private JSONObject item(Uri uri,String path,boolean deletable)throws Exception{
        String name=uri.getLastPathSegment();long size=-1;
        if("file".equals(uri.getScheme())){File f=new File(uri.getPath());name=f.getName();size=f.length();}
        else try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()){name=cursor.getString(0);if(!cursor.isNull(1))size=cursor.getLong(1);}}
        if(size<0)throw new IOException("Dimensione non disponibile: "+name+". Scaricalo prima sul telefono.");
        long modified=0;
        try {
            if("file".equals(uri.getScheme())) modified=new File(uri.getPath()).lastModified();
            else {String column=DocumentsContract.isDocumentUri(this,uri)?DocumentsContract.Document.COLUMN_LAST_MODIFIED:MediaStore.MediaColumns.DATE_MODIFIED;
                try(Cursor c=getContentResolver().query(uri,new String[]{column},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))modified=c.getLong(0);}}
        }catch(Exception ignored){}
        return new JSONObject().put("uri",uri.toString()).put("name",name==null?"file":name).put("size",size).put("modified",modified)
            .put("path",path+"/"+safeName(name==null?"file":name)).put("disk",selectedDisk).put("status","pending").put("deletable",deletable);
    }
    private void collectGallery(){
        task(()->{JSONArray incoming=new JSONArray();
            Uri[] collections={MediaStore.Images.Media.EXTERNAL_CONTENT_URI,MediaStore.Video.Media.EXTERNAL_CONTENT_URI};
            for(int i=0;i<collections.length;i++){
                String permission=Build.VERSION.SDK_INT>=33?(i==0?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_MEDIA_VIDEO):Manifest.permission.READ_EXTERNAL_STORAGE;
                if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED && !(Build.VERSION.SDK_INT>=34&&checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED))continue;
                try(Cursor cursor=getContentResolver().query(collections[i],new String[]{MediaStore.MediaColumns._ID},null,null,null)){
                    if(cursor!=null)while(cursor.moveToNext()){Uri uri=ContentUris.withAppendedId(collections[i],cursor.getLong(0));incoming.put(item(uri,batch()+ (i==0?"/Foto":"/Video"),true));if(incoming.length()>30000)throw new IOException("Seleziona una cartella o gruppi più piccoli di file.");}
                }
            }
            finishCollection(incoming);return null;});
    }
    private void otherFiles(){
        if(!ready())return;
        new AlertDialog.Builder(this).setTitle("Cosa vuoi importare?").setItems(new String[]{"Scegli file, documenti o musica","Scegli una cartella intera","Salva una copia dei contatti"},(d,w)->{
            if(w==0)picker("*/*",null);
            if(w==1){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,102);}
            if(w==2){if(checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED)exportContacts();else requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},202);}
        }).setNegativeButton("Annulla",null).show();
    }
    private void picker(String mime,String[] mimes){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType(mime);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);if(mimes!=null)intent.putExtra(Intent.EXTRA_MIME_TYPES,mimes);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,101);}
    private void persist(Uri uri,int flags){try{getContentResolver().takePersistableUriPermission(uri,flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));}catch(Exception ignored){}}
    @Override public void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==301){
            if(result==RESULT_OK){ArrayList<JSONObject> deleted=new ArrayList<>(pendingMedia);pendingMedia.clear();task(()->{markDeleted(deleted);runOnUiThread(()->notice("Spazio liberato",deleted.size()+" originali rimossi. La copia resta sul disco."));return null;});}
            else{pendingMedia.clear();notice("Operazione annullata","Gli originali sono ancora sul telefono.");}return;
        }
        if(result!=RESULT_OK||data==null)return;
        if(request==101){ArrayList<Uri> selected=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)selected.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)selected.add(data.getData());
            for(Uri uri:selected)persist(uri,data.getFlags());
            task(()->{JSONArray incoming=new JSONArray();for(Uri uri:selected)incoming.put(item(uri,batch()+"/File",true));finishCollection(incoming);return null;});
        }
        if(request==102&&data.getData()!=null){Uri tree=data.getData();persist(tree,data.getFlags());task(()->{JSONArray incoming=new JSONArray();collectTree(tree,DocumentsContract.getTreeDocumentId(tree),batch()+"/Cartelle",incoming);finishCollection(incoming);return null;});}
    }
    private void collectTree(Uri tree,String document,String path,JSONArray incoming)throws Exception{
        if(path.split("/").length>40)throw new IOException("Cartella troppo profonda");
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,document);
        try(Cursor cursor=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            if(cursor==null)return;
            while(cursor.moveToNext()){
                String id=cursor.getString(0),name=cursor.getString(1),mime=cursor.getString(2);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))collectTree(tree,id,path+"/"+safeName(name),incoming);
                else incoming.put(item(DocumentsContract.buildDocumentUriUsingTree(tree,id),path,true));
                if(incoming.length()>30000)throw new IOException("Scegli gruppi di cartelle più piccoli.");
            }
        }
    }
    private void finishCollection(JSONArray incoming){
        runOnUiThread(()->{busy=false;
            if(incoming.length()==0){notice("Nessun file trovato","Non vedo file accessibili. Prova a sceglierli manualmente o controlla i permessi nelle impostazioni del telefono.");return;}
            new AlertDialog.Builder(this).setTitle("Salviamo questi file?").setMessage(incoming.length()+" file selezionati.\nDestinazione: "+selectedName+".\nGli originali restano sul telefono.")
                .setNegativeButton("Annulla",null).setPositiveButton("Salva sul disco",(d,w)->task(()->{int added=Queue.add(this,incoming);runOnUiThread(()->{if(added==0)notice("Già presenti","Questi file sono già nell’elenco dei salvataggi. Puoi vedere le copie nell’archivio o riprendere una copia interrotta.");else startTransfers();});return null;})).show();
        });
    }
    private void exportContacts(){
        task(()->{File folder=new File(getFilesDir(),"exports");folder.mkdirs();File output=new File(folder,"Contatti-"+System.currentTimeMillis()+".vcf");int exported=0;
            try(OutputStream out=new FileOutputStream(output);Cursor cursor=getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,new String[]{ContactsContract.Contacts.LOOKUP_KEY},null,null,null)){
                if(cursor!=null)while(cursor.moveToNext()){
                    Uri uri=Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI,cursor.getString(0));
                    try(InputStream in=getContentResolver().openInputStream(uri)){if(in!=null){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);out.write('\n');exported++;}}
                }
            }
            if(exported==0){output.delete();runOnUiThread(()->notice("Nessun contatto","Non ho trovato contatti da esportare."));}
            else finishCollection(new JSONArray().put(item(Uri.fromFile(output),batch()+"/Contatti",false)));return null;});
    }
    private void startTransfers(){
        if(TransferService.running)return;
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},203);
        startForegroundService(new Intent(this,TransferService.class));
    }
    private void browse(String path){
        if(selectedDisk.isEmpty()){notice("Scegli il disco","Tocca Scegli disco prima di aprire l’archivio.");return;}
        task(()->{JSONArray files=new Api(this).json("GET","/api/files?disk="+Api.encode(selectedDisk)+"&path="+Api.encode(path),null).getJSONArray("files");
            runOnUiThread(()->{home=false;archivePath=path;frame();text("File salvati",30,true);text(selectedName,18,false);
                button("← Indietro",false,()->{if(path.isEmpty())showHome();else browse(path.contains("/")?path.substring(0,path.lastIndexOf('/')):"");});
                if(files.length()==0)text("Questa cartella è vuota. I file appariranno qui dopo il salvataggio.",18,false);
                for(int i=0;i<files.length();i++){JSONObject f=files.optJSONObject(i);button((f.optBoolean("directory")?"Cartella · ":"")+f.optString("name")+(f.optBoolean("directory")?"":"\n"+bytes(f.optLong("size"))),false,()->{if(f.optBoolean("directory"))browse(f.optString("path"));else openFile(f);});}
            });return null;});
    }
    private void openFile(JSONObject item){
        new AlertDialog.Builder(this).setTitle("Apri una copia").setMessage("Scarico una copia temporanea sul telefono per aprire questo file. Il file sul disco rimane al suo posto.")
            .setNegativeButton("Annulla",null).setPositiveButton("Apri",(d,w)->task(()->{long size=item.getLong("size");
                if(getCacheDir().getUsableSpace()<size+32*1024*1024)throw new IOException("Spazio insufficiente sul telefono per aprire questo file");
                File folder=new File(getCacheDir(),"opened");folder.mkdirs();File out=new File(folder,UUID.randomUUID()+"-"+safeName(item.getString("name")));
                new Api(this).download("/api/download?disk="+Api.encode(selectedDisk)+"&path="+Api.encode(item.getString("path")),out);
                runOnUiThread(()->{Uri uri=Uri.parse("content://com.dvlce.pidrive.files/"+Uri.encode(out.getName()));Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,getContentResolver().getType(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try{startActivity(intent);}catch(ActivityNotFoundException e){notice("File scaricato","Sul telefono manca un’app per aprire questo tipo di file.");}});return null;})).show();
    }
    private void confirmCleanup(){
        if(TransferService.running){notice("Salvataggio in corso","Attendi la fine del salvataggio o metti in pausa.");return;}
        new AlertDialog.Builder(this).setTitle("Libera spazio sul telefono").setMessage("Controllo che le copie siano ancora integre sul disco e che gli originali non siano cambiati. Poi ti mostro quanti file si possono rimuovere.")
            .setNegativeButton("Annulla",null).setPositiveButton("Controlla le copie",(d,w)->checkCleanup()).show();
    }
    private void checkCleanup(){
        task(()->{ArrayList<JSONObject> eligible=new ArrayList<>();int skipped=0;Api api=new Api(this);JSONArray items=Queue.load(this);
            for(int i=0;i<items.length();i++){
                JSONObject item=items.getJSONObject(i);if(!"verified".equals(item.optString("status"))||!item.optBoolean("deletable"))continue;
                try{Uri source=Uri.parse(item.getString("uri"));String current=Api.hash(getContentResolver().openInputStream(source));
                    JSONObject copy=api.json("GET","/api/uploads/"+item.getString("upload")+"/verify",null);
                    if(copy.optBoolean("verified")&&current.equals(copy.optString("sha256"))&&current.equals(item.optString("sha256")))eligible.add(item);else skipped++;
                }catch(Exception e){skipped++;}
            }
            int omitted=skipped;
            runOnUiThread(()->{busy=false;
                if(eligible.isEmpty()){notice("Nessun originale da rimuovere","Nessun file soddisfa tutti i controlli. Collega il disco e controlla i permessi del telefono. Gli originali restano al loro posto.");return;}
                new AlertDialog.Builder(this).setTitle("Rimuovere "+eligible.size()+" originali?").setMessage("Le copie sono state verificate sul disco.\n"+(omitted>0?omitted+" file esclusi perché non verificabili.\n":"")+"Rimuovo gli originali selezionati dal telefono. Android potrebbe chiederti un’ulteriore conferma.")
                    .setNegativeButton("Tieni gli originali",null).setPositiveButton("Libera spazio",(d,w)->deleteVerified(eligible)).show();
            });return null;});
    }
    private void deleteVerified(ArrayList<JSONObject> candidates){
        task(()->{Api api=new Api(this);ArrayList<JSONObject> media=new ArrayList<>(),documents=new ArrayList<>();ArrayList<Uri> uris=new ArrayList<>();int skipped=0;
            // Repeat both checks immediately before deletion, after the user's confirmation.
            for(JSONObject item:candidates){
                try{Uri source=Uri.parse(item.getString("uri"));String hash=Api.hash(getContentResolver().openInputStream(source));JSONObject copy=api.json("GET","/api/uploads/"+item.getString("upload")+"/verify",null);
                    if(!copy.optBoolean("verified")||!hash.equals(copy.optString("sha256"))||!hash.equals(item.optString("sha256"))){skipped++;continue;}
                    Uri mediaUri="media".equals(source.getAuthority())?source:null;
                    if(mediaUri==null&&Build.VERSION.SDK_INT>=29&&DocumentsContract.isDocumentUri(this,source)){
                        try{mediaUri=MediaStore.getMediaUri(this,source);}catch(Exception ignored){}
                    }
                    if(mediaUri!=null&&Build.VERSION.SDK_INT>=30&&media.size()<500){media.add(item);uris.add(mediaUri);}
                    else if(DocumentsContract.isDocumentUri(this,source)&&DocumentsContract.deleteDocument(getContentResolver(),source))documents.add(item);
                    else skipped++;
                }catch(Exception e){skipped++;}
            }
            markDeleted(documents);int omitted=skipped;
            runOnUiThread(()->{busy=false;
                if(!media.isEmpty() && Build.VERSION.SDK_INT>=30){
                    pendingMedia=media;
                    try{startIntentSenderForResult(MediaStore.createDeleteRequest(getContentResolver(),uris).getIntentSender(),301,null,0,0,0);}
                    catch(Exception e){pendingMedia.clear();failure(e);}
                }else notice("Controllo completato",documents.size()+" originali rimossi. "+omitted+" lasciati sul telefono. Le copie restano sul disco.");
            });return null;});
    }
    private void markDeleted(ArrayList<JSONObject> deleted)throws Exception{
        HashSet<String> ids=new HashSet<>();for(JSONObject item:deleted)ids.add(item.optString("upload"));JSONArray items=Queue.load(this);
        for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);if(ids.contains(item.optString("upload")))item.put("status","deleted");}Queue.save(this,items);
    }
    private static String bytes(long size){if(size<1024)return size+" B";if(size<1024*1024)return String.format(Locale.ITALY,"%.1f KB",size/1024.0);if(size<1024L*1024*1024)return String.format(Locale.ITALY,"%.1f MB",size/(1024.0*1024));return String.format(Locale.ITALY,"%.1f GB",size/(1024.0*1024*1024));}
    @Override public void onBackPressed(){if(!home){if(archivePath.isEmpty())showHome();else browse(archivePath.contains("/")?archivePath.substring(0,archivePath.lastIndexOf('/')):"");}else super.onBackPressed();}
}
