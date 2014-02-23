(ns herolabs-test.apns.message
  (:use midje.sweet
        herolabs.apns.message
        ))



(facts "about message assembly"
  (with-badge {} 1) => (just {:aps {:badge 1}})
  (with-sound {} :default) => (just {:aps {:sound "default"}})
  (with-sound {} "default") => (just {:aps {:sound "default"}})
  (with-sound {} :chimes) => (just {:aps {:sound "chimes"}})
  (with-standard-alert {} "Hello world") => (just {:aps {:alert "Hello world"}})
  (with-action-loc-key {} "TEST") => (just {:aps {:alert {:action-loc-key "TEST"}}})
  (with-loc-key {} "TEST") => (just {:aps {:alert {:loc-key "TEST"}}})
  (with-loc-args {} "foo") => (just {:aps (just {:alert (just {:loc-args (just ["foo"])})})})
  (with-loc-args {} ["foo" "bar"]) => (just {:aps (just {:alert (just {:loc-args (just ["foo" "bar"])})})})
  (with-alert-body {} "TEST") => (just {:aps {:alert {:body "TEST"}}})
  (meta (herolabs.apns.message/with-payload (with-meta {:foo :bar} {:a :b}) {:pay :load})) => {:a :b}
  )

