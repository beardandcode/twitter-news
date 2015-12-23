(ns com.beardandcode.twitter-news.processor.recorder
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [cheshire.core :as json]
            [metrics.meters :refer [mark! meter]]
            [com.beardandcode.twitter-news.streaming :as s]))

(defn record-stream [out-file]
  (fn [in-chan metric-registry]
    (let [o (io/output-stream out-file)
          written-meter (meter metric-registry "written")]
      (async/go-loop []
        (if-let [val (async/<! in-chan)]
          (do (.write o (.getBytes (str (json/generate-string val) "\n") "UTF-8"))
              (mark! written-meter)
              (recur))
          (.close o))))))
