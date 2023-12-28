(ns walkthrough
  (:require
   [casa.squid.jack :as jack]
   [casa.squid.midi :as midi]))

(def c (jack/client :walkthrough))

(def midi-in (jack/midi-in-port c :midi-in))
(def audio-left (jack/audio-out-port c :left))
(def audio-right (jack/audio-out-port c :right))

(jack/ports c #{:midi :out})
;; => ["Midi-Bridge:Midi Through:(capture_0) Midi Through Port-0"
;;     "Midi-Bridge:Launchkey Mini 2:(capture_0) Launchkey Mini LK Mini MIDI"
;;     "Midi-Bridge:Launchkey Mini 2:(capture_1) Launchkey Mini LK Mini InContro"]

(jack/connect "Midi-Bridge:Launchkey Mini 2:(capture_0) Launchkey Mini LK Mini MIDI" midi-in)

(jack/register
 c
 :process
 ::my-process-loop
 (fn [client frame-count]
   (run! (fn [[bytes frame]]
           (prn (midi/event bytes)))
         (jack/read-midi-events midi-in))
   true))

(jack/transport-pos)
;; => {:frame 0, :frame-rate 44100, :usecs 17405102959, :valid #{}, :state :stopped}

(jack/start-transport!)

(jack/transport-pos)
;;=>
{:frame 92160,
 :frame-rate 44100,
 :usecs 17493219351,
 :valid #{},
 :state :rolling}

(jack/stop-transport!)
(jack/seek-transport! 0)


(require '[casa.squid.jack.transport-leader :as l])

(l/initialize!)

(jack/transport-pos)
;;=>
{:valid #{:bbt},
 :tick 0,
 :beat-type 4.0,
 :beat 1,
 :frame-rate 44100,
 :beats-per-bar 4.0,
 :frame 0,
 :ticks-per-beat 1920.0,
 :bar 1,
 :state :stopped,
 :usecs 18019315664,
 :bpm 120.0,
 :bbt-offset 0}


(jack/register
 c
 :process
 ::my-process-loop
 (fn [client frame-count]
   (run! (fn [[bytes frame]]
           (prn (midi/event bytes)))
         (jack/read-midi-events midi-in))
   true))

(def wt-size 100)
(def wavetable (float-array wt-size))
(dotimes [i wt-size]
  (aset wavetable i (float (Math/sin (* 2 Math/PI (/ i wt-size))))))

;; Note that using `cycle` here will put some pressure on the garbage collector,
;; because a lot of Seq objects need to be created and discarded. It would be
;; preferable to keep track of indices in the wavetable manually. I'm doing it
;; this way to keep the implementation easy to understand.
(def wt-cycle (volatile! (cycle wavetable)))

(jack/register
 c
 :process
 ::sine-wave
 (fn [client frame-count]
   (let [buffer (.getFloatBuffer audio-left)]
     (.rewind buffer)
     (doseq [^float v (take frame-count @wt-cycle)]
       (.put buffer v))
     (vswap! wt-cycle #(drop frame-count %)))
   true))

;; What's cool is because the wavetable is a (mutable) float-array, you can
;; manipulate. To stop the sound, set it back to all zeroes.
(dotimes [i wt-size]
  (aset wavetable i (float 0)))
