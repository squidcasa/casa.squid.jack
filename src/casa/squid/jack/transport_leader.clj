(ns casa.squid.jack.transport-leader
  "A Jack Transport leader that will set beat/bar/tick position information."
  (:require
   [casa.squid.jack :as jack])
  (:import
   (java.util EnumSet)
   (org.jaudiolibs.jnajack JackPosition JackPositionBits)))

(set! *warn-on-reflection* true)

(defonce
  ^{:doc "The timing information that everything else is based on. We just get a
frame number and frame rate from Jack, the rest we calculate based on this. You
can swap! this to change e.g. the tempo."}
  timing
  (atom {:beats-per-minute 120
         :beats-per-bar 4
         :beat-type 4
         :ticks-per-beat 1920}))

(defn calculate-timings [frame-rate frame {:keys [beats-per-minute
                                                  beats-per-bar
                                                  beat-type
                                                  ticks-per-beat]
                                           :as timing}]
  (let [frames-per-beat (/ frame-rate (/ beats-per-minute 60))
        frames-per-tick (/ frames-per-beat ticks-per-beat)
        ticks-per-bar (* ticks-per-beat beats-per-bar)
        ticks-total (/ frame frames-per-tick)
        tick (mod (/ frame frames-per-tick) ticks-per-beat)
        beat (mod (/ frame frames-per-beat) beats-per-bar)
        bar (/ frame frames-per-beat beats-per-bar)
        bar-start-tick (- ticks-total (mod ticks-total ticks-per-bar))]
    ;; coerce to the types that JackPosition uses
    {:bar (inc (int bar))
     :beat (inc (int beat))
     :tick (int tick)
     :bar-start-tick (double bar-start-tick)
     :beats-per-bar (float beats-per-bar)
     :beat-type (float beat-type)
     :ticks-per-beat (double ticks-per-beat)
     :beats-per-minute (double beats-per-minute)}))

(defn populate-jack-pos [^JackPosition pos {:keys [^int bar
                                                   ^int beat
                                                   ^int tick
                                                   ^double bar-start-tick
                                                   ^float beats-per-bar
                                                   ^float beat-type
                                                   ^double ticks-per-beat
                                                   ^double beats-per-minute]}]
  (doto pos
    (.setBar bar)
    (.setBeat beat)
    (.setTick tick)
    (.setBarStartTick bar-start-tick)
    (.setBeatsPerBar beats-per-bar)
    (.setBeatType beat-type)
    (.setTicksPerBeat ticks-per-beat)
    (.setBeatsPerMinute beats-per-minute)
    (.setValid
     (EnumSet/of
      JackPositionBits/JackPositionBBT
      ;; JackPositionBits/JackBBTFrameOffset
      ;; JackPositionBits/JackPositionTimecode
      ))))

(defn initialize!
  "Attempt to become transport leader, and register the callback that allows us to
  control the current JackPosition."
  ([]
   (initialize! true))
  ([force?]
   (initialize! @jack/default-client force?))
  ([client force?]
   (jack/make-transport-leader client force?)
   (jack/register
    client
    :update-position
    ::main-loop
    (fn [client state nframes ^JackPosition pos new-pos?]
      ;;(print ":") (flush)
      #_(if new-pos?)
      (populate-jack-pos pos (calculate-timings (.getFrameRate pos) (.getFrame pos) @timing))
      #_(prn (bean pos))
      ))))

(comment
  (initialize!)
  (jack/seek-transport! 0)
  (jack/start-transport!)
  (jack/stop-transport!)

  (jack/transport-pos)

  (calculate-timings 48000 0 @timing)

  (swap! timing assoc :ticks-per-beat 1920)
  (swap! timing assoc :beats-per-minute 90)
  )
