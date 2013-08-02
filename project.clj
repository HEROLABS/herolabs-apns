(defproject herolabs/apns "0.2.2"
  :description "A simple lightweight library to use with the Apple push notification service."
  :url "https://github.com/HEROLABS/herolabs-apns"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :min-lein-version "2.0.0"
  :warn-on-reflection true
  :dependencies [[commons-codec "1.6"]
                 [clj-json "0.5.0"]
                 [midje "1.4.0"]
                 [org.jboss.netty/netty "3.2.7.Final"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.slf4j/slf4j-api "1.7.2"]
                 [ch.qos.logback/logback-core "1.0.9"]
                 [ch.qos.logback/logback-classic "1.0.9"]
                 ]
  :profiles {:dev {:dependencies [[jonase/kibit "0.0.4"]
                                  [midje "1.4.0"]
                                  [bultitude "0.1.7"]]
             :plugins [[lein-midje "2.0.4"]]}}
  )
