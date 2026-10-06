package com.dvlce.pidrive;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;

public final class TransferService extends Service {
    static volatile boolean running=false, pause=false;
    static volatile String message="Pronto per salvare i tuoi file";
    static volatile long done=0, total=0;
    static volatile int completed=0, count=0;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private static final int NOTICE=7;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("transfers", "Salvataggio file", NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"transfers").setSmallIcon(com.dvlce.pidrive.R.drawable.ic_drive)
            .setContentTitle("Pi Drive").setContentText(message).setContentIntent(open).setOngoing(running)
            .setProgress(100,total==0 ? 0 : (int)Math.min(100,done*100/total),total==0).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if (running) return START_NOT_STICKY;
        pause=false; running=true;
        if (Build.VERSION.SDK_INT>=29) startForeground(NOTICE,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTICE,notification());
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PiDrive:transfer");
        wake.acquire(6*60*60*1000L);
        worker=new Thread(this::transfer,"PiDriveUpload"); worker.start();
        return START_NOT_STICKY;
    }
    private void publish(String value) {
        message=value;
        getSystemService(NotificationManager.class).notify(NOTICE,notification());
    }
    private void transfer() {
        JSONArray items=Queue.load(this);
        try {
            Api api=new Api(this);
            total=0; done=0; completed=0; count=0;
            for (int i=0;i<items.length();i++) {
                JSONObject item=items.getJSONObject(i);
                if ("deleted".equals(item.optString("status"))) continue;
                count++;
                total+=Math.max(0,item.optLong("size",0));
                if ("verified".equals(item.optString("status"))) { done+=Math.max(0,item.optLong("size",0)); completed++; }
            }
            for (int i=0;i<items.length()&&!pause;i++) {
                JSONObject item=items.getJSONObject(i);
                if ("verified".equals(item.optString("status")) || "deleted".equals(item.optString("status"))) continue;
                Uri uri=Uri.parse(item.getString("uri"));
                long size=item.getLong("size");
                if (size<0) throw new IOException("Dimensione sconosciuta: scegli il file dalla memoria del telefono");
                publish("Salvataggio " + (completed+1) + "/" + count + " · " + item.getString("name"));
                JSONObject remote;
                if (item.optString("upload").isEmpty()) {
                    remote=api.json("POST","/api/uploads",new JSONObject().put("disk",item.getString("disk"))
                        .put("path",item.getString("path")).put("size",size));
                    item.put("upload",remote.getString("id")); item.put("remotePath",remote.getString("path"));
                    Queue.save(this,items);
                } else remote=api.json("GET","/api/uploads/"+item.getString("upload"),null);
                String endpoint="/api/uploads/"+item.getString("upload");
                if (remote.optBoolean("verified")) {
                    String local=Api.hash(getContentResolver().openInputStream(uri));
                    JSONObject verified=api.json("GET",endpoint+"/verify",null);
                    if (!verified.optBoolean("verified")||!local.equals(verified.optString("sha256")))
                        throw new IOException("L’originale è cambiato: selezionalo di nuovo prima di liberare spazio");
                    item.put("sha256",local).put("status","verified"); Queue.save(this,items);
                    done+=size; completed++; continue;
                }
                long offset=remote.getLong("received"), position=0, baseDone=done;
                if (offset<0||offset>size) throw new IOException("Stato del trasferimento non valido");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                try (InputStream in=getContentResolver().openInputStream(uri)) {
                    if(in==null) throw new IOException("File non accessibile");
                    byte[] buffer=new byte[4*1024*1024];
                    while (position<size&&!pause) {
                        int wanted=(int)Math.min(buffer.length,size-position), actual=0;
                        while(actual<wanted) { int n=in.read(buffer,actual,wanted-actual); if(n<0) break; actual+=n; }
                        if(actual!=wanted) throw new IOException("Il file è cambiato o non è più disponibile");
                        digest.update(buffer,0,actual);
                        if(position+actual>offset) {
                            int start=(int)Math.max(0,offset-position);
                            byte[] chunk=Arrays.copyOfRange(buffer,start,actual);
                            long expected=position+start;
                            Exception last=null;
                            for(int attempt=0;attempt<3;attempt++) {
                                if(pause) break;
                                try {
                                    JSONObject state=api.json("GET",endpoint,null);
                                    long received=state.getLong("received");
                                    if(received==position+actual) { last=null; break; }
                                    if(received!=expected) throw new IOException("Posizione non valida: metti in pausa e riprendi");
                                    api.request("PUT",endpoint,chunk,expected,Api.hex(MessageDigest.getInstance("SHA-256").digest(chunk)));
                                    last=null; break;
                                } catch(Exception e) { last=e; Thread.sleep(1000L*(attempt+1)); }
                            }
                            if(last!=null) throw last;
                        }
                        position+=actual;
                        done=baseDone+position;
                        publish("Salvataggio " + (completed+1) + "/" + count + " · " + item.getString("name"));
                    }
                    if(!pause && in.read()!=-1) throw new IOException("Il file è cambiato durante il trasferimento");
                }
                if(pause) break;
                String hash=Api.hex(digest.digest());
                publish("Controllo del file salvato · " + item.getString("name"));
                JSONObject receipt=api.json("POST",endpoint+"/complete",new JSONObject().put("sha256",hash));
                if(!receipt.optBoolean("verified")||!hash.equals(receipt.optString("sha256")))
                    throw new IOException("Copia non verificata. L’originale resta sul telefono.");
                item.put("sha256",hash).put("status","verified"); Queue.save(this,items);
                completed++; done=baseDone+size;
            }
            publish(pause ? "In pausa. Tocca Riprendi quando vuoi." : "File salvati e verificati sul disco");
        } catch(Exception e) { publish("Salvataggio interrotto: " + friendly(e)); }
        finally {
            running=false;
            if(wake!=null && wake.isHeld()) wake.release();
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }
    static String friendly(Exception e) {
        if(e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException || e instanceof java.net.UnknownHostException)
            return "Controlla Wi-Fi, Raspberry e Tailscale, poi tocca Riprendi.";
        return e.getMessage()==null ? "Riprova. Gli originali sono ancora sul telefono." : e.getMessage();
    }
    @Override public void onTimeout(int startId,int type) { pause=true; message="Limite Android raggiunto. Apri l’app e riprendi."; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    @Override public void onDestroy() { pause=true; if(wake!=null&&wake.isHeld())wake.release(); super.onDestroy(); }
}
