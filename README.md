# casa.squid.jack - Clojure Wrapper for Jack JNA

The Jack API provides a way for audio applications on Linux to deal with audio
and midi signals, as well as providing a transport API, so applications can
synchronize musically.

Jack JNA provides a Java wrapper for the C API, cljack provides a Clojure
wrapper for the Java API.

## Installation

Currently only available through git. Look on Github what the most recent commit
SHA is, and add something like this to your `deps.edn`.

```clj
;; deps.edn
{:deps {net.arnebrasseur/cljack {:git/url "https://github.com/plexus/cljack" :git/sha "..."}}}
```

## Usage

