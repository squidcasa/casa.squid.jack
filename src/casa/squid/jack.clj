(ns casa.squid.jack
  "Wrapper for Jack (Jack Audio Connection Kit) midi

  Allows creating in/out ports, and registering callbacks of various types.

  Callback name + argument list of callback function

  - :process [client frames]
  - :buffer-size-changed [client buffersize]
  - :client-registered [client name]
  - :client-unregistered [client name]
  - :ports-connected [client port-name-1 port-name-2]
  - :ports-disconnected [client port-name-1 port-name-2]
  - :port-registered [client port-name]
  - :port-unregistered [client port-name]
  - :sample-rate-changed [client rate]
  - :client-shutdown [client]
  - :update-position [client state frame pos new-pos]

  See [[register]]/[[unregister]], which work analogous
  to [[add-watch!]] (idempotent, REPL safe, etc).
  "
  (:require
   [clojure.pprint :as pprint])
  (:import
   (java.util EnumSet)
   (org.jaudiolibs.jnajack
    Jack
    JackClient
    JackException
    JackMidi
    JackMidi$Event
    JackOptions
    JackPosition
    JackPositionBits
    JackPortFlags
    JackPortType
    JackProcessCallback
    JackShutdownCallback
    JackBufferSizeCallback
    JackPortConnectCallback
    JackPortRegistrationCallback
    JackSampleRateCallback
    JackShutdownCallback
    JackSyncCallback
    JackClientRegistrationCallback
    JackTimebaseCallback
    JackTransportState
    JackGraphOrderCallback
    JackStatus)))

(set! *warn-on-reflection* true)

;; The jack docs say to reuse a single instance... Might want to make this
;; thread-local or lock on it.
(defonce ^JackMidi$Event global-midi-event (JackMidi$Event.))
(defonce ^JackPosition global-jack-pos (JackPosition.))

(defonce !instance (delay (Jack/getInstance)))

(defn instance ^Jack [] @!instance)

(defonce
  ^{:doc "Registry of JackClientWrapper instances (keyword->JackClientWrapper).
  Used to make calls to [[client]] idempotent"}
  clients
  (atom {}))

(defprotocol Registry
  (register [this type key val] "Register a callback")
  (unregister [this type key] "Remove a callback")
  (lookup [this type key] "Find a given callback"))

(defmulti init-callback!
  "When a callback of a given type is registered for the first time, register an
  actual Java callback object for that type, which dispatches to all of our
  registered callbacks."
  (fn [client type] type))

(def callback-pairs
  "Some pairs of callbacks types are bundled in a single Java callback interface,
  in these cases we need to know that if one of them is already initialized, the
  other is too."
  {:client-unregistered :client-registered
   :ports-disconnected :ports-connected
   :port-unregistered :port-registered})

(defrecord JackClientWrapper [^JackClient client registry]
  Registry
  (register [this t k value]
    (swap! registry
           (fn [registry]
             ;; FIXME: despite being inside the swap this can potentially still
             ;; cause issues when used at the same time from multiple threads,
             ;; since Clojure can retry this update function, and we want this
             ;; init to happen exactly once.
             (when-not (or (get registry t)
                           (get registry (get callback-pairs t))
                           (get registry (get (into {} (map (juxt val key)) callback-pairs) t)))
               (init-callback! this (get callback-pairs t t)))
             (assoc-in registry [t k] value))))
  (unregister [_ t k]
    (swap! registry update t dissoc k))
  (lookup [_ t k]
    (get-in @registry [t k]))

  ;; Convenience, deref to get the actual JackClient
  clojure.lang.IDeref
  (deref [_]
    @client)

  Object
  (toString [_]
    (str "#<JackClientwrapper " (.getName client) ">")))

(defmethod print-method JackClientWrapper [x ^java.io.Writer writer]
  (.append writer (.toString ^Object x)))

(defmethod pprint/simple-dispatch JackClientWrapper [x]
  (.write ^java.io.Writer *out* (.toString ^Object x)))

(defmacro registry-callback [client registry cb-type set-cb-type & methods]
  `(~(symbol (str "." set-cb-type))
    ~client
    (reify ~(symbol (str "Jack" cb-type "Callback"))
      ~@(for [[method k args] (partition 3 methods)]
          `(~method ~(into '[_] args)
            (reduce (fn [ok?# [k# cb#]]
                      ;; If a callback returns falsy, then any remaining
                      ;; callbacks of that type are skipped
                      (when ok?#
                        (try
                          (cb# ~@args)
                          (catch Exception e#
                            (println "Error in" '~method "callback" k#)
                            (println e#)
                            ok?#))))
                    true
                    (~k @~registry)))))
    ~@(drop (- (count methods) (mod (count methods) 3)) methods)))

(comment
  (macroexpand-1 '(registry-callback client registry ClientRegistration
                                     clientRegistered :client-registered [client name]
                                     clientUnregistered :client-unregistered [client name]))
  (require 'clojure.reflect)
  (clojure.reflect/reflect JackBufferSizeCallback))

(defn make-client
  "Construct a new Jack client. Prefer [[client]] which is idempotent."
  [name]
  (let [status (EnumSet/noneOf JackStatus)
        client (.openClient (instance)
                            name
                            (EnumSet/of JackOptions/JackNoStartServer)
                            status)
        registry (atom {})]
    #_(when (seq status)
        (println "make-client:" (map str status)))
    ;; These can't be registered once a client has been activated, so we do it
    ;; eagerly during init.
    (registry-callback client registry Process setProcessCallback
                       process :process [client frames])
    (registry-callback client registry BufferSize setBuffersizeCallback
                       buffersizeChanged :buffer-size-changed [client buffersize])
    (registry-callback client registry SampleRate setSampleRateCallback
                       sampleRateChanged :sample-rate-changed [client rate])
    (.activate client)
    (let [c (->JackClientWrapper client registry)]
      (swap! clients assoc name c)
      c)))

(defn client
  "Get a client for a given name, creating it if it doesn't exist."
  [client-name]
  (let [name (if (keyword? client-name)
               (subs (str client-name) 1)
               client-name)]
    (or (get @clients name) (make-client name))))

(defonce default-client (delay (client :cljack)))

(defn jack-port-type ^JackPortType [kw]
  (case kw
    :audio JackPortType/AUDIO
    :midi JackPortType/MIDI
    ;; throws if kw is not recognized
    ))

(def port-flags
  {:can-monitor JackPortFlags/JackPortCanMonitor
   :in JackPortFlags/JackPortIsInput
   :out JackPortFlags/JackPortIsOutput
   :physical JackPortFlags/JackPortIsPhysical
   :terminal JackPortFlags/JackPortIsTerminal})

(defn jack-port-flags ^EnumSet [kws]
  (reduce (fn [^EnumSet es kw]
            (doto es (.add (get port-flags kw))))
          (EnumSet/noneOf JackPortFlags)
          kws))

(defn port
  "Get a virtual audio or midi port."
  ([name type flags]
   (port @default-client name type flags))
  ([^JackClientWrapper client name type flags]
   (assert (keyword? name))
   (if-let [port (lookup client type name)]
     port
     (let [port (.registerPort ^JackClient (:client client)
                               (subs (str name) 1)
                               (jack-port-type type)
                               (jack-port-flags flags))]
       (register client type name port)
       port))))

(defn midi-in-port
  "Get a virtual midi input port with a given name. Idempotent."
  ([name]
   (midi-in-port @default-client name))
  ([client name]
   (port client name :midi :in)))

(defn midi-out-port
  "Get a midi output name for a given client with a given name. Idempotent."
  ([name]
   (midi-in-port @default-client name))
  ([client name]
   (port client name :midi :out)))

(defn read-midi-event
  "Read one of the midi events that are available in this processing cycle for the
  given port. Unless you're doing something very specific you probably want to
  use [[read-midi-events]] instead."
  [port idx]
  #_(locking global-midi-event)
  (JackMidi/eventGet global-midi-event port idx)
  (let [msg (byte-array (.size global-midi-event))]
    (.read global-midi-event msg)
    [msg (.time global-midi-event)]))

(defn read-midi-events
  "Read midi events that happened in this processing cycle for a given input port.
  Call within a processing callback."
  [port]
  (doall
   (for [idx (range (JackMidi/getEventCount port))]
     (read-midi-event port idx))))

(defn write-midi-event
  "Write a midi event to a given port at a given time (frame offset)."
  [port time msg]
  (JackMidi/eventWrite port time msg (count msg)))

(defn filter-pipe
  "Utility for creating midi filters, forward all messages from `in` to `out` if
  they satisfy `pred`."
  [in out pred]
  (try
    (JackMidi/clearBuffer out)
    (dotimes [idx (JackMidi/getEventCount in)]
      (let [[msg time] (read-midi-event in idx)]
        (when (pred msg)
          (write-midi-event out time msg))))
    true
    (catch JackException e
      (println "JackException:" e)
      true)))

(defn start-transport!
  "Start (play) Jack Transport control"
  ([]
   (start-transport! @default-client))
  ([client]
   (.transportStart ^JackClient (:client client))))

(defn stop-transport!
  "Stop Jack Transport control"
  ([]
   (stop-transport! @default-client))
  ([client]
   (.transportStop ^JackClient (:client client))))

(defn seek-transport!
  "Move the Jack Transport position to a specific frame."
  ([frame]
   (seek-transport! @default-client frame))
  ([client frame]
   (.transportLocate ^JackClient (:client client) frame)))

(defn ports
  "Get a vector of Jack ports (strings). Optionally takes a set of keywords to
  filter by type and port flags, e.g. #{:midi :out}, #{:audio :physical}.
  See [[port-flags]] for options."
  ([]
   (ports @default-client))
  ([client]
   (into [] (.getPorts (instance) (:client client) nil nil nil)))
  ([client type-and-flags]
   (into
    []
    (.getPorts
     (instance) (:client client) nil
     (cond
       (every? type-and-flags [:midi :audio])
       nil
       (:audio type-and-flags)
       JackPortType/AUDIO
       (:midi type-and-flags)
       JackPortType/MIDI
       :else nil)
     (let [flags (EnumSet/noneOf JackPortFlags)]
       (doseq [[kw flag] port-flags]
         (when (get type-and-flags kw)
           (.add flags flag)))
       flags)))))

(comment
  (ports (client :vibeflow) #{:midi :in}))

(defn connections
  "Get all jack connections as a sequence of `from`/`to` pairs (both String)."
  ([]
   (connections @default-client))
  ([client]
   (for [from (ports client #{:out})
         to (connections client from)]
     [from to]))
  ([client port]
   (into [] (.getAllConnections (instance) (:client client) port))))

(defn connect
  "Connect two jack ports, `from` and `two` are strings."
  ([conns]
   (doseq [[from to] conns]
     (connect from to)))
  ([from to]
   (connect @default-client from to))
  ([client from to]
   (.connect (instance) (:client client) from to)))

(defn disconnect
  "Connect two jack ports, `from` and `two` are strings."
  ([from to]
   (disconnect @default-client from to))
  ([client from to]
   (.disconnect (instance) (:client client) from to)))

(defn connect!
  "Sets jack connections to exactly the given connections, given as a list of
  pairs (port names, String). Will sever any existing connection that is not in
  the list. Ports that don't currently exist are ignored.

  Together with [[connections]] this gives you a way to capture the current
  state of a patch as EDN, and restore it later."
  ([conns]
   (connect! @default-client conns))
  ([client conns]
   (let [ports (ports client)
         existing (set (for [from ports
                             to (connections client from)]
                         (vec (sort [from to]))))]
     (doseq [[from to] existing]
       (when-not (some #{[from to] [to from]} conns)
         (try
           (disconnect client to from)
           (catch Exception _))))
     (doseq [[from to] conns]
       (when-not (some #{[from to] [to from]} existing)
         (when (and (some #{from} ports) (some #{to} ports))
           (try
             (connect client from to)
             (catch Exception _))))))))

(def position-bits
  {JackPositionBits/JackPositionBBT :bbt
   JackPositionBits/JackPositionTimecode :timecode
   JackPositionBits/JackBBTFrameOffset :bbt-frame-offset
   JackPositionBits/JackAudioVideoRatio :audio-video-rate
   JackPositionBits/JackVideoFrameOffset :video-frame-offset})

(defn pos->map [^JackPosition pos]
  (let [valid (into #{} (map position-bits (.getValid pos)))]
    (cond-> {:frame (.getFrame pos)
             :frame-rate (.getFrameRate pos)
             :usecs (.getUsecs pos)
             :valid valid}
      (:bbt valid)
      (assoc
       :bar (.getBar pos)
       :beat (.getBeat pos)
       :tick (.getTick pos)
       :ticks-per-beat (.getTicksPerBeat pos)
       :bpm (.getBeatsPerMinute pos)
       :beats-per-bar (.getBeatsPerBar pos)
       :beat-type (.getBeatType pos)
       :bbt-offset 0)
      (:timecode valid)
      (assoc
       :frame-time (.getFrameTime pos)
       :bbt-offset (.getBbtOffset pos)
       :next-time (.getNextTime pos)
       :audio-frames-per-video-frame (.getAudioFramesPerVideoFrame pos)
       :video-offset (.getVideoOffset pos))
      (:bbt-frame-offset valid)
      (assoc
       :bbt-offset (.getBbtOffset pos)))))

(defn transport-pos
  "Get the current transport position as a map."
  ([]
   (transport-pos @default-client))
  ([client]
   (let [state (.transportQuery ^JackClient (:client client) global-jack-pos)]
     (assoc
      (pos->map global-jack-pos)
      :state
      (cond
        (= state JackTransportState/JackTransportStopped)     :stopped
        (= state JackTransportState/JackTransportRolling)     :rolling
        (= state JackTransportState/JackTransportLooping)     :looping
        (= state JackTransportState/JackTransportStarting)    :starting
        (= state JackTransportState/JackTransportNetStarting) :net-starting ;; Waiting for sync ready on the network
        )))))

(defmethod init-callback! :process [_ _])
(defmethod init-callback! :buffer-size-changed [_ _])
(defmethod init-callback! :sample-rate-changed [_ _])


(defmethod init-callback! :client-registered [{:keys [^JackClient client registry]} _]
  (registry-callback client registry ClientRegistration setClientRegistrationCallback
                     clientRegistered :client-registered [client name]
                     clientUnregistered :client-unregistered [client name]))

(defmethod init-callback! :ports-connected [{:keys [^JackClient client registry]} _]
  (registry-callback client registry PortConnect setPortConnectCallback
                     portsConnected :ports-connected [client port-name-1 port-name-2]
                     portsDisconnected :ports-disconnected [client port-name-1 port-name-2]))

(defmethod init-callback! :port-registered [{:keys [^JackClient client registry]} _]
  (registry-callback client registry PortRegistration setPortRegistrationCallback
                     portRegistered :port-registered [client port-name]
                     portUnregistered :port-unregistered [client port-name]))



(defmethod init-callback! :client-shutdown [{:keys [^JackClient client registry]} _]
  (registry-callback client registry Shutdown onShutdown
                     clientShutdown :client-shutdown [client]))

(defmethod init-callback! :graph-order-changed [{:keys [^JackClient client registry]} _]
  (registry-callback client registry GraphOrder setGraphOrderCallback
                     graphOrderChanged :graph-order-changed [client]))

;; Immediately throws, not clear why
;; (registry-callback client registry Sync setSyncCallback
;;                    syncPosition :sync-position [client position state])

(defn make-transport-leader
  "Attempt at becoming the Jack Transport leader, this requires you provide a
  `:update-position` callback, which takes the current transport state and
  frames, and based on that determines the other position fields, e.g
  beat/bar/tick.

  Will fail if another process is already leader, unless `force?` is set to
  true."
  ([]
   (make-transport-leader @default-client))
  ([client-or-force?]
   (make-transport-leader (if (boolean? client-or-force?)
                            @default-client
                            client-or-force?)
                          (if (boolean? client-or-force?)
                            client-or-force?
                            false)))
  ([client force?]
   (let [{:keys [ registry]} client]
     (registry-callback ^JackClient (:client client) registry Timebase setTimebaseCallback
                        updatePosition :update-position [client state frame pos new-pos]
                        (not force?)))
   client))

(defmethod init-callback! :update-position [client _]
  (make-transport-leader client false))
