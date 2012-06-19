(ns herolabs.apns.ssl
  (:use [clojure.tools.logging]
        [clojure.java.io :only (input-stream)])
  (:import [java.security Security KeyStore]
           [javax.net.ssl KeyManager KeyManagerFactory SSLContext SSLEngine TrustManager TrustManagerFactory X509TrustManager]
           [java.security.cert X509Certificate CertificateException])
  )



(defn ssl-context [& {:keys [store-path store-pass cert-pass trust-managers]}]
  "Creates a SSL context to use the SSL encryption. The function takes a :store-path argument for the (JKS) keystore
  to lookup the certificate. You may also specify :store-pass and :cert-pass for the keystore and certificate password
  or :trust-managers to provide an array of trust managers to use.
  "
  (let [algorithm (if-let [a (Security/getProperty "ssl.KeyManagerFactory.algorithm")] a "SunX509")
        keystore (doto (KeyStore/getInstance "JKS")
      (.load (input-stream store-path) (char-array store-pass)))
        kmf (doto (KeyManagerFactory/getInstance algorithm)
      (.init keystore (char-array cert-pass)))
        tmf (if trust-managers trust-managers (doto (TrustManagerFactory/getInstance algorithm)
                                                (.init keystore)
                                                (.getTrustManagers)))
        context (doto (SSLContext/getInstance "TLS")
      (.init (.getKeyManagers kmf) trust-managers nil))
        ]
    context
    )
  )

(defn ssl-engine [context & {:keys [use-client-mode] :or {use-client-mode true}}]
  "Creates an SSL engine"
  (let [engine (.createSSLEngine context)]
    (if use-client-mode
      (doto engine (.setUseClientMode use-client-mode))
      engine)
    )
  )

(defn ssl-engine-factory [context & {:keys [use-client-mode] :or {use-client-mode true}}]
  "Creates an SSL engine"
  (fn [] (let [engine (.createSSLEngine context)]
           (if use-client-mode
             (doto engine (.setUseClientMode use-client-mode))
             engine)
           )))



(defn naive-trust-managers [& {:keys [trace] :or [trace false]}]
  "Creates a very naive trust manager that will accept all certificates."
  (into-array (list (proxy [javax.net.ssl.X509TrustManager] []
                      (getAcceptedIssuers [] (make-array X509Certificate 0))
                      (checkClientTrusted [chain auth-type]
                        (when trace (info "Unknown client certificate:" (.getSubjectDN (get chain 0))))
                        )
                      (checkServerTrusted [chain auth-type]
                        (when trace (info "Unknown server certificate:" (.getSubjectDN (get chain 0))))
                        )
                      )))
  )