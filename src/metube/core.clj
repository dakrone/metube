(ns metube.core
  (:require [robert.bruce :refer [try-try-again *try*]]
            [clojure.java.shell :as sh]
            [clojure.java.io :refer [copy reader]]
            [clojure.tools.logging :as log]
            [compojure.core :refer :all]
            [compojure.handler :as handler]
            [immutant.cache :as cache]
            [immutant.messaging :as msg])
  (:import (java.io ByteArrayOutputStream)))

(def qn "queue.notifications")
(def youtube-dl-cmd "/usr/local/bin/youtube-dl")
(def download-dir "/Users/hinmanm/Downloads")

(def stats-cache (cache/cache "metube-stats" :persist true))

(defonce youtube-dl-enabled?
  (= 0 (:exit (sh/sh "which" youtube-dl-cmd))))

(defn download
  [url]
  (when youtube-dl-enabled?
    (log/info "Starting metube download for" url)
    (let [resp (sh/with-sh-dir download-dir (sh/sh youtube-dl-cmd url "-t"))
          exit (:exit resp)]
      (when-not (zero? exit)
        (log/warn "Non-zero exit downloading:" url)
        (log/debug "Output:" resp)
        (throw (Exception. (str "error downloading " url (:err resp))))))
    true))

(defn get-active []
  (get stats-cache :active 0))

(defn inc-active []
  (cache/put stats-cache :active (inc (get-active))))

(defn dec-active []
  (cache/put stats-cache :active (dec (get-active))))

(defn download-youtube-url [url]
  (log/info "Received download request for:" url)
  (try
    (inc-active)
    ;; TODO retries
    (msg/publish qn (str "Beginning download of " url))
    (let [resp (download url)
          resp (try-try-again {:sleep 2000
                               :tries 5
                               :catch [Exception]}
                              #(download url))]
      (when resp
        (msg/publish qn (str "Successfully downloaded " url))))
    (catch Throwable e
      (msg/publish qn (str "Unable to download: " url ", reason: " e)))
    (finally
      (try
        (dec-active)
        (catch Exception e
          (log/warn e "Exception decrementing active cache"))))))

(defn enqueue-handler
  "Handler for enqueuing youtube download requests"
  [request]
  (try
    (let [url (slurp (:body request))]
      (if (and (string? url) (not (empty? url)))
        (do
          (msg/publish qn (str "Queuing download of " url))
          (msg/publish "queue.metube" url)
          {:status 200
           :body (str {:success true} "\n")
           :headers {"Content-Type" "application/edn"}})
        {:status 500
         :body (str {:success false :exception "No URL specified"} "\n")
         :headers {"Content-Type" "application/edn"}}))
    (catch Throwable e
      {:status 500
       :body (str {:success false :exception (str e)} "\n")
       :headers {"Content-Type" "application/edn"}})))

(defn active-requests
  "Returns number of active downloads"
  []
  {:status 200
   :body (str {:active (get stats-cache :active 0)} "\n")
   :headers {"Content-Type" "application/edn"}})

(defroutes metube-routes
  (GET "/active" [] (active-requests))
  (ANY "/" request (enqueue-handler request)))

(def handler metube-routes)
