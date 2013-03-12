(ns herolabs.apns.ssl
  (:use [clojure.tools.logging]
        [clojure.java.io :only (input-stream)]
        [clojure.string :only (join)])
  (:import [org.apache.commons.codec.digest DigestUtils]
           [java.security Security KeyStore]
           [javax.net.ssl KeyManager KeyManagerFactory SSLContext SSLEngine TrustManager TrustManagerFactory X509TrustManager]
           [java.security.cert Certificate X509Certificate CertificateException CertificateFactory]))


(defn load-keystore
  "Loads a keystore"
  [path password type]
  (doto (KeyStore/getInstance type)
    (.load (input-stream path) (char-array password))))


(defn ssl-context
  "Creates a SSL context to use the SSL encryption. The function takes a :store-path argument for the (JKS) keystore
  to lookup the certificate. You may also specify :store-pass and :cert-pass for the keystore and certificate password
  or :trust-managers to provide an array of trust managers to use. "
  [& {:keys [keystore store-path store-pass store-type key-pass trust-managers algorithm]
      :or {algorithm (or (Security/getProperty "ssl.KeyManagerFactory.algorithm") "SunX509")
           store-type "JCEKS"}}]
  (let [keystore (if keystore keystore (load-keystore store-path store-pass store-type))
        kmf (doto (KeyManagerFactory/getInstance algorithm)
              (.init keystore (char-array key-pass)))
        tms (if trust-managers trust-managers (.getTrustManagers (doto
                                                                   (TrustManagerFactory/getInstance algorithm)
                                                                   (.init ^KeyStore keystore)
                                                                   )))
        context (doto (SSLContext/getInstance "TLS")
                  (.init (.getKeyManagers kmf) tms nil))]
    context))

(defn aliases [^KeyStore keystore]
  (letfn [(get-aliases [^java.util.Enumeration enum] (lazy-seq
                                (when (.hasMoreElements enum)
                                  (cons (.nextElement enum) (get-aliases enum)))))]
    (get-aliases (.aliases keystore))))


(defn get-cert-chain [path type]
  (let [factory (CertificateFactory/getInstance type)
        certs (.generateCertificates factory (input-stream path))]
    (into-array Certificate certs)))

(defn keystore [& {:keys [key-path key-pass key-type cert-path cert-type] :or {key-type "PKCS12" cert-type "X.509"}}]
  (let [key-store (load-keystore key-path key-pass key-type)
        key (.getKey ^java.security.KeyStore key-store (first (aliases key-store)) (char-array key-pass))
        certs (get-cert-chain cert-path cert-type)]
    (doto
      (KeyStore/getInstance "JKS")
      (.load nil (char-array key-pass))
      (.setKeyEntry "apple-push-service" key (char-array nil) certs))))


(defn ssl-engine [^SSLContext context & {:keys [use-client-mode] :or {use-client-mode true}}]
  "Creates an SSL engine"
  (let [^SSLEngine engine (.createSSLEngine context)]
    (if use-client-mode
      (doto engine (.setUseClientMode use-client-mode))
      engine)))

(defn ssl-engine-factory [^SSLContext context & {:keys [use-client-mode] :or {use-client-mode true}}]
  "Creates an SSL engine"
  (fn [] (let [engine (.createSSLEngine context)]
          (if use-client-mode
            (doto engine (.setUseClientMode use-client-mode))
            engine))))


(defn naive-trust-managers [& {:keys [trace] :or [trace false]}]
  "Creates a very naive trust manager that will accept all certificates."
  (into-array (list (proxy [javax.net.ssl.X509TrustManager] []
                      (getAcceptedIssuers [] (make-array X509Certificate 0))
                      (checkClientTrusted [ chain auth-type]
                        (when trace
                          (info "Unknown client certificate:" (.getSubjectDN ^X509Certificate (get chain 0)))))
                      (checkServerTrusted [ chain auth-type]
                        (when trace
                          (info "Unknown server certificate:" (.getSubjectDN ^X509Certificate (get chain 0)))))))))
