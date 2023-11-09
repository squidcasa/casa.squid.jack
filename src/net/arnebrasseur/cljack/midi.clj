(ns net.arnebrasseur.cljack.midi
  "MIDI message parsing and construction logic.

  Interpreting and constructing of midi message as byte arrays (signed) or long
  arrays (unsigned).

  ``` clojure
  (def msg (byte-array 3 [130 64 100])) ;; Middle C on channel 2 with velocity 100

  (channel msg)    ;; => 2
  (event-type msg) ;; => :note-on
  (note msg)       ;; => 64
  (velocity msg)   ;; => 100

  (event msg) ;; => [2 :note-on 64 100]
  ```
  "
  (:require [clojure.set :as set])
  (:import (java.lang.reflect Array)))

(set! *unchecked-math* :warn-on-boxed)

;; https://www.songstuff.com/recording/article/midi_message_format/

;; https://en.wikipedia.org/wiki/MIDI_timecode
;; https://en.wikipedia.org/wiki/MIDI_beat_clock
;; https://en.wikipedia.org/wiki/MIDI_Machine_Control

(def event-types
  {0x80 :note-off
   0x90 :note-on
   0xA0 :poly-aftertouch
   0xB0 :cc
   0xC0 :program-change
   0xD0 :channel-aftertouch
   0xE0 :pitch-wheel
   0xF0 :sysex})

(def special-types
  {0xF0 :sysex-start
   0xF7 :sysex-end

   0xF2 :song-pointer
   0xF3 :song-select
   0xF6 :tune-request

   0xF1 :mtc-quarter-frame
   0xF8 :clock
   0xF9 :measure
   0xFA :start
   0xFB :continue
   0xFC :stop
   0xFE :active-sensing

   ;; Reset when sent over the wire, meta when read from a file
   0xFF :reset-meta

   0xF4 :unused
   0xF5 :unused
   0xFD :unused})

(defn b->l
  "Signed byte (-127 to 128) to long (0 to 255)"
  ^long [b]
  (long (bit-and (byte b) 0xFF)))

(defn l->b
  "Long to signed byte"
  [^long l]
  (unchecked-byte l))

(defmacro aget-byte [arr idx]
  `(byte (aget ~(with-meta arr {:tag 'bytes}) ~idx)))

;; (defn bytes->longs ^longs [^bytes bytes]
;;   (let [len (Array/getLength bytes)
;;         longs (long-array len)]
;;     (dotimes [idx len]
;;       (aset-long longs idx (b->l (aget bytes idx))))
;;     longs))

(defn status ^long [^bytes msg]
  (b->l (aget msg 0)))

(defn event-val ^long [msg]
  (bit-and 0xF0 (aget-byte msg 0)))

(defn special? [msg]
  (= 0xF0 (event-val msg)))

(defn event-type [msg]
  (if (special? msg)
    (get special-types (status msg))
    (get event-types (event-val msg))))

(defn channel ^long [msg]
  (bit-and 0x0F (aget-byte msg 0)))

(defn data1
  "Return the first data byte

  Semantics depend on the message type, e.g. note number, controller number,
  program number"
  ^long [msg]
  (bit-and 0xFF (aget-byte msg 1)))

(defn data2
  "Return the second data byte

  Semantics depend on the message type, e.g. velocity, pressure"
  ^long [msg]
  (bit-and 0xFF (aget-byte msg 2)))

(defn data-full
  "Return the data bytes combined into a single integer

  Used for pitch-wheel messages."
  ^long [msg]
  (bit-or (aget-byte msg 1) (bit-shift-left (aget-byte msg 2) 8)))

;; Semantic aliases, can be used in your code once you know the type of a
;; message to get more meaningful code.
(def note data1)
(def controller data1)
(def velocity data2)
(def cc-data data2)
(def pitchwheel data-full)

(defn event
  "Convert a message (array) to an event vector.

  Given a three element message array, return a four element vector with [channel
  event-type data-byte-1 data-byte-2]"
  [^bytes msg]
  (into [(channel msg) (event-type msg)] (rest msg)))

(def event-vals (into
                 (set/map-invert event-types)
                 (set/map-invert special-types)))

(defn message
  "Convert an event vector to a message array

  The inverse of [[event]]."
  ([v]
   (apply message v))
  ([channel type & data]
   (let [vals (cons (bit-or (long (event-vals type)) (long channel)) data)]
     (byte-array (count vals) vals))))

(comment
  (message 0 :note-on 64 127))
