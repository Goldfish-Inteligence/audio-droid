# Wunschliste

 * Funktionalität in Foreground Service auslagern
 * Empfang und Senden ermöglichen
 * Mikrofon als Mono benutzen (Erfordert Anpassungen in den Bindings)
 * Auswahl der Quelle und Zielmikros
 * IP anpassbar machen
 * Abfragen von Konfiguration (JSON) von Server, dabei Identifizierungsmerkmal schicken
 * ~~Server URL durch QR Code scannen~~ Server mittels DNS Service Discovery finden
 * Batteriestand alle 3 Minuten an Server melden
 * Vom Server gesetzten Name und ähnlich im UI anzeigen
 * Benachrichtigung anzeigen wenn aktiv
 * Server mitteilen ob `android.hardware.audio.pro` gesetzt ist
 * Durchgehendes Vibrieren wenn Verbindung abgebrochen
 * UI aufhübschen

# Ressourcen

 * https://developer.android.com/ndk/guides/audio/audio-latency
 * https://gavv.github.io/articles/roc-tutorial/
 * https://github.com/roc-streaming/roc-toolkit
 * https://roc-streaming.org/toolkit/docs/internals.html

Es empfiehlt sich das roc-toolkit zu bauen. Dort fallen die receive
und send Programme raus, welche zur Entwicklung erstmal ausreichen.
