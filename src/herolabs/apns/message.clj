(ns herolabs.apns.message)


(defn with-badge [message number] (assoc-in message [:aps :badge ] number))

(defn with-sound [message sound]
  (assoc-in message [:aps :sound ] (name sound))
  )

(defn with-standard-alert [message body]
  (assoc-in message [:aps :alert ] body)
  )

(defn with-action-loc-key [message key]
  (if key
    (assoc-in message [:aps :alert :action-loc-key ] key)
    message)
  )

(defn with-loc-key [message key]
  (if key
    (assoc-in message [:aps :alert :loc-key ] key)
    message)
  )


(defn with-loc-args [message args]
  (if-not (empty? args)
    (assoc-in message [:aps :alert :loc-args ] (if (sequential? args) args (list args)))
    message)
  )

(defn with-payload [message payload]
  (if-not (empty? payload)
    (merge (dissoc payload :aps) message)
    message)
  )

(defn with-alert-body [message body]
  (if key
    (assoc-in message [:aps :alert :body ] body)
    message)
  )

