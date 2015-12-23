(ns user
  (:require [clojure.core.async :as async]
            [clojure.java.shell :as shell]
            [clojure.repl :refer :all]
            [clojure.test :refer [run-all-tests]]
            [clojure.tools.namespace.repl :refer [refresh refresh-all]]
            [com.stuartsierra.component :as component]
            [environ.core :refer [env]]
            [reloaded.repl :refer [system init start stop go reset clear]]
            [com.beardandcode.components.web-server :refer [port]]
            [com.beardandcode.tools :refer [scss]]
            [com.beardandcode.twitter-news.processor :as processor]
            [com.beardandcode.twitter-news.stats :as stats]
            [com.beardandcode.twitter-news.system :refer [new-system new-recorder-system new-recorded-system
                                                          new-live-system]]))

(selmer.parser/cache-off!)

(reloaded.repl/set-init!
 #(new-system env))

(defn url [] (str "http://localhost:" (-> system :web port) "/"))
(defn open! [] (shell/sh "open" (url)))

(defn refresh-and [f]
    (refresh :after (symbol "user" f)))

(defn test-all [] (run-all-tests #"^com.beardandcode.twitter-news.*-test$"))
(defn test-unit [] (run-all-tests #"^com.beardandcode.twitter-news.(?!integration).*-test$"))
(defn test-integration [] (run-all-tests #"^com.beardandcode.twitter-news.integration.*-test$"))

(def control-chan (async/chan))
(def twitter-creds {:consumer-key (:twitter-consumer-key env)
                    :consumer-secret (:twitter-consumer-secret env)
                    :token (:twitter-token env)
                    :secret (:twitter-secret env)})

(defn kill [] (async/>!! control-chan :kill))

(defn run-system [base-system]
  (let [system (component/start base-system)]
    (async/go
      (loop [check-status (async/timeout 1000)]
        (let [[_ ch] (async/alts! [check-status control-chan])]
          (if (= ch check-status)
            (do (->> system
                     (filter (fn [[name impl]] (satisfies? stats/StatsProvider impl)))
                     (map (fn [[name impl]] {name (stats/stats impl)}))
                     (apply merge)
                     clojure.pprint/pprint)
                (recur (async/timeout 1000)))
            (component/stop system)))))))

(defn record [terms follows]
  (run-system (new-recorder-system twitter-creds "stream.out" terms follows)))

(defn replay []
  (run-system (new-recorded-system "stream.out")))

(defn live [terms follows]
  (run-system (new-live-system twitter-creds terms follows)))
