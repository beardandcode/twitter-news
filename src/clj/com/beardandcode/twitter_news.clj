(ns com.beardandcode.twitter-news
  (:gen-class)
  (:require [environ.core :refer [env]]
            [com.stuartsierra.component :as component]
            [com.beardandcode.twitter-news.system :refer [new-system]]))

(defn -main []
  (component/start (new-system env)))
