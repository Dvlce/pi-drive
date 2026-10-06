# Pi Drive

Un’app Android piccola, in italiano, per salvare foto, video, file, cartelle e una copia dei contatti su un disco fisico collegato a una macchina Linux, incluso Raspberry Pi 5. Pensata per chi vuole pochi pulsanti grandi e istruzioni chiare.

L’app usa componenti Android nativi, senza WebView o librerie esterne. Il server usa soltanto la libreria standard Python. I file restano sul disco scelto, nella cartella `PiDrive`.

## Per iniziare dal telefono

1. Scarica e installa **Pi-Drive.apk** dalla [release più recente](https://github.com/Dvlce/pi-drive/releases/latest). Android può chiedere di consentire l’installazione dal browser.
2. Un familiare configura **Impostazioni** una sola volta: indirizzo del server e codice privato.
3. Tocca **Scegli disco**. Il disco di sistema è protetto; le altre destinazioni disponibili mostrano lo spazio libero. Il collegamento di un disco già preparato non comporta formattazione.
4. Tocca **Salva foto e video** oppure **Scegli altri file**. L’app chiede i permessi necessari e conferma quanti file stai per salvare.
5. Guarda la barra, la percentuale e i file salvati. Puoi mettere in pausa e riprendere; dopo un’interruzione riapri l’app e tocca **Riprendi salvataggio**.
6. **Vedi file salvati** mostra le cartelle reali del disco. Aprire un file scarica una copia temporanea sul telefono.

Per usare un indirizzo Tailscale (`100.x.x.x`), anche il telefono deve essere collegato alla stessa rete Tailscale. Sul Wi-Fi domestico si può impostare l’indirizzo LAN della macchina. La porta predefinita è `8090`. I trasferimenti HTTP sulla LAN usano l’autenticazione con codice; per accedere da fuori casa usa Tailscale oppure un reverse proxy HTTPS.

## Liberare spazio

**Libera spazio sul telefono** controlla nuovamente entrambe le copie, confrontando il contenuto con SHA-256. Sono candidati soltanto gli originali importati da questa installazione dell’app, già verificati, ancora accessibili e non modificati. Il disco deve essere collegato e raggiungibile.

L’utente conferma la rimozione. Per la galleria, Android 11 o successivo mostra anche la propria conferma: fino a 500 elementi per operazione. Per i documenti selezionati, la rimozione dipende dal permesso di scrittura e dal supporto del provider Android. Gli elementi non verificabili restano sul telefono. I contatti esportati non vengono cancellati.

L’app non accede ai dati privati delle altre app. Per chat, password e altri dati protetti usa le funzioni di esportazione dell’app interessata, poi importa i file esportati. Android può limitare le cartelle selezionabili e consentire solo una parte della galleria: l’app importa soltanto ciò che il sistema rende accessibile. Per i media su Android 8–10 la pulizia può richiedere una selezione tramite il gestore documenti.

## Installare il server

Richiede Linux, Python 3.9 o successivo, `lsblk`, `udisksctl`, `sudo` e systemd. L’installer predefinito usa l’utente `dvlce`: per altri utenti adatta servizio, helper e regola sudoers prima di installare. Nessun disco viene formattato.

```sh
git clone https://github.com/Dvlce/pi-drive.git
cd pi-drive
python3 -m unittest discover -s tests -v
sudo sh deploy/install.sh
sudo systemctl status pi-drive
```

Il codice di accesso viene generato in `/etc/pi-drive.env`, leggibile soltanto da root. Inserisci nell’app il valore di `PIDRIVE_TOKEN`. Non pubblicare quel file.

Il processo HTTP gira come utente normale. Un helper root, installato separatamente e non modificabile dall’utente del servizio, collega solo volumi fisici validati, esclude il disco di sistema e assegna la sola cartella `PiDrive` all’utente. Il rilevamento dei dischi è aggiornato a ogni richiesta; nell’app l’elenco e lo stato si aggiornano periodicamente.

Per montare automaticamente un disco ext4 dedicato all’avvio, dopo aver verificato il suo UUID e che non sia già usato da altri mount:

```sh
sudo python3 deploy/enable_disk.py UUID-DEL-DISCO
sudo systemctl daemon-reload
sudo systemctl restart pi-drive
```

Questo comando crea un’unità di mount dedicata per `/srv/pidrive`; non modifica `/etc/fstab`. Si interrompe se trova configurazioni o cartelle incompatibili. Non usarlo per spostare mount di altri progetti. Per dischi NTFS/exFAT già preparati usa la scelta del disco nell’app e assicurati che Linux abbia il relativo driver.

## Compilare Android

Richiede JDK 17, SDK Android 35 e build-tools 35.0.0.

```sh
cd android
export JAVA_HOME=/percorso/jdk17
export ANDROID_HOME=/percorso/android-sdk
./gradlew :app:assembleDebug
```

Per un APK release firmato, dalla radice del repository:

```sh
python3 deploy/build_apk.py --java-home /percorso/jdk17 --android-home /percorso/android-sdk
```

La chiave viene conservata in `~/.pi-drive-signing`, fuori dal repository; `android/keystore.properties` è ignorato da Git. Conserva la chiave per firmare gli aggiornamenti. L’APK è in `dist/Pi-Drive.apk`.

## Trasferimenti e verifica

Il server riceve blocchi di massimo 4 MiB con hash e offset. I file incompleti sono nella cartella nascosta `.incoming` del disco selezionato. L’app conserva la coda e riprende dal numero di byte confermato dal server. Dopo il trasferimento viene verificato anche il file definitivo sul disco. I file esistenti non vengono sovrascritti: un nome in conflitto riceve un suffisso.

Per completare la copia serve temporaneamente spazio per il file incompleto e quello definitivo: il server richiede circa il doppio della dimensione del file più un piccolo margine. Lo scollegamento del disco interrompe le operazioni; il server non ripiega sul disco di sistema. Le copie temporanee aperte sul telefono sono nella cache privata dell’app e Android può liberarle.

## Verifiche

```sh
python3 -m unittest discover -s tests -v
```

I test coprono autenticazione, percorsi non validi, collegamenti simbolici, file già esistenti, blocchi danneggiati, ripresa, integrità delle copie, disco scollegato e protezione del disco di sistema. La build Android viene verificata dalla CI.
