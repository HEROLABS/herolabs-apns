(defproject herolabs/apns "0.1.10"
  :description "A simple lightweight library to use with the Apple push notification service."
  :url "https://github.com/HEROLABS/herolabs-apns"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [commons-codec "1.6"]
                 [clj-json "0.5.0"]
                 [midje "1.4.0"]
                 [org.jboss.netty/netty "3.2.7.Final"]
                 [org.clojure/tools.logging "0.2.3"]
                 [org.slf4j/slf4j-api "1.6.4"]
                 [ch.qos.logback/logback-core "1.0.0"]
                 [ch.qos.logback/logback-classic "1.0.0"]
                 ]
  :dev-dependencies [[lein-midje "1.0.10"]]
  ; :aot :all
  )
