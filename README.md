# casa.squid.jack

<!-- badges -->
[![cljdoc badge](https://cljdoc.org/badge/casa.squid/jack)](https://cljdoc.org/d/casa.squid/jack) [![Clojars Project](https://img.shields.io/clojars/v/casa.squid/jack.svg)](https://clojars.org/casa.squid/jack)
<!-- /badges -->

Jack Audio Connection Kit - Clojure API

## Features

`casa.squid.jack` makes it possible to connect to the Jack audio server, the de
facto standard for audio and music production on Linux, for audio, MIDI, and
transport control.

<!-- installation -->
## Installation

To use the latest release, add the following to your `deps.edn` ([Clojure CLI](https://clojure.org/guides/deps_and_cli))

```
casa.squid/jack {:mvn/version "0.0.0"}
```

or add the following to your `project.clj` ([Leiningen](https://leiningen.org/))

```
[casa.squid/jack "0.0.0"]
```
<!-- /installation -->

## Rationale

This library sprang forth from a desire for better MIDI handling on Linux, since
the built-in Java libaries `javax.sound.midi.*` were found lacking. Soon
transport handling and audio loop callback handling was added.

JNA Jack exposes the Jack API to Java/JVM, this libary makes it more
idiomatically consumable from Clojure. Particular care was taken to make it
REPL-friendly.

## Usage

Working with Jack requires that you first make a Jack client. `jack/client`
takes a string or keyword, and returns a client with the given name. This call
is memoized, so it's safe to call it multiple times, you'll get the same client
back.

```clj
(require '[casa.squid.jack :as jack])

(def c (jack/client :my-app))
```

Note that for many API calls passing in the client is optional, if omitted a
client with name `"Clojure"` is used.

Next you can create ports for this client, MIDI or audio, input or output.

```clj
(def midi-in (midi-in-port c :midi-in))
(def audio-left (audio-out-port c :left))
(def audio-right (audio-out-port c :right))
```

I have a little MIDI keyboard, let's find out what the port name is, and connect
it to our Jack client.

```clj
(jack/ports c #{:midi :out})
;; => ["Midi-Bridge:Midi Through:(capture_0) Midi Through Port-0"
;;     "Midi-Bridge:Launchkey Mini 2:(capture_0) Launchkey Mini LK Mini MIDI"
;;     "Midi-Bridge:Launchkey Mini 2:(capture_1) Launchkey Mini LK Mini InContro"]

(jack/connect "Midi-Bridge:Launchkey Mini 2:(capture_0) Launchkey Mini LK Mini MIDI" midi-in)
```

Now we can receive MIDI events. This has to happen inside a `:process` loop, so we register a process callback.

```clj
(jack/register
 c
 :process
 ::my-process-loop
 (fn [client frame-count]
   (run! (fn [[bytes frame]]
           (prn (midi/event bytes)))
         (jack/read-midi-events midi-in))
   true))
```

A few things to note here. `::my-process-loop` is a name we pick, if we call
this again with the same name, the previous callback will be replaced (compare
with `add-watch!`), making it convenient for REPL use. We can also `unregister`
the callback again.

The callback has to return `true`, returning falsey will cause subsequent
callbacks of the same type to be skipped.

`read-midi-events` returns a sequence of pairs, the MIDI event as a byte-array,
and a number indicating the frame offset of when this event occured within the
current processing cycle. The `casa.squid.midi` namespace has functions for
working with MIDI events, and converting them back and forth between byte arrays
and Clojure representation. (This namespace doesn't depend on anything Jack
specific, and may be split into its own mini-library in the future.)

Now if we press some keys or turn a knob, this shows up in the REPL:

```
;; channel / event type / note / velocity
[0 :note-on 59 72]
[0 :note-off 59 0]
[0 :note-on 59 69]
[0 :note-off 59 0]
;; channel / event type / controller number / value (0-127)
[0 :cc 23 47]
[0 :cc 23 48]
```

These are all the supported callbacks that you can register, with the arguments
of the callback function:

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

### Dealing with Jack Transport

Jack Transport allows multiple applications to synchronize, reacting to the same
"play" or "pause" events, and agreeing on the current time signature, bar, beat,
etc. So you could for instance have a DAW containing audio recordings play in
sync to a step sequencer with drums.

Only one application at a time can be the transport leader. The leader is
responsible for computing the current musical position, based on the current
frame number. Note that not every leader will actually compute and set values
like bar, beat, tick, or beats-per-bar. They may be `0`, so be careful,
especially with divisions.

There are two ways to participate in Jack's transport and position. The simply
is to simply request the current position and transport state. You can do this
at any time, and from any thread (not just the processing thread).

```clj
(jack/transport-pos)
;; => {:frame 0, :frame-rate 44100, :usecs 17405102959, :valid #{}, :state :stopped}

;; Start rolling
(jack/start-transport!)

;; frame count is moving up, `:state` has changed to `:rolling`
(jack/transport-pos)
;;=>
{:frame 92160,
 :frame-rate 44100,
 :usecs 17493219351,
 :valid #{},
 :state :rolling}

;; Stop rolling
(jack/stop-transport!)

;; Seek back to the start (frame 0)
(jack/seek-transport! 0)
```

To become the transport leader you first call `make-transport-leader`, and then
register an `:update-position` callback.

`make-transport-leader` will fail if there is already a transport leader, unless
you pass a `force?` flag of true.

```clj
(jack/make-transport-leader true)
(jack/register
 client
 :update-position
 ::main-loop
 (fn [client state nframes ^JackPosition pos new-pos?]
   (populate-jack-pos pos (calculate-timings (.getFrameRate pos) (.getFrame pos) @timing))))
```

See `casa.squid.jack.transport-leader` for an example implementation.

```clj
(require '[casa.squid.jack.transport-leader :as l])

(l/initialize!)
```

You can see now that `jack/transport-pos` returns much richer timing
information, including bpm, bbt (bar/beat/tick), time signature
(beats-per-bar/beat-type).

```
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
```

### Producing audio

To generate audio, you write to the buffer of a given audio port during the
process callback. Here's an example that uses a precomputed wavetable of a sine
wave.

```clj
(def wt-size 100) ;; at an audio rate of 44100Hz this will produce a 441 Hz sine wave, i.e. a slightly sharp A.
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
```

<!-- opencollective -->
<!-- /opencollective -->

<!-- contributing -->
<!-- /contributing -->

<!-- license -->
## License

Copyright &copy; 2023 Arne Brasseur and Contributors

Licensed under the term of the Mozilla Public License 2.0, see LICENSE.
<!-- /license -->
