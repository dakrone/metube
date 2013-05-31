(ns metube.core
  (:require [robert.bruce :refer [try-try-again *try*]]
            [clojure.java.shell :as sh]
            [clojure.java.io :refer [reader]]
            [immutant.messaging :as msg]))

(def qn "queue.notifications")
(def youtube-dl-cmd "youtube-dl")

(defonce youtube-dl-enabled?
  (= 0 (:exit (sh/sh "which" "youtube-dl"))))

(defn download
  [url]
  (when youtube-dl-enabled?
    (let [resp (sh/sh youtube-dl-cmd url "-t")
          exit (:exit resp)]
      (when-not (zero? exit)
        (println "Non-zero exit downloading:" url)
        (throw (Exception. (str "error downloading " url (:err resp))))))
    true))

(defn download-youtube-url [url]
  (println "Received download request for:" url)
  (try
    (let [resp (try-try-again
                :sleep 5000
                :tries 5
                :error-hook
                (fn [e]
                  (let [s (str "Error downloading: " url ", trying again. ["
                               *try* "/5] tries - " (str e))]
                    (println s)
                    (msg/publish qn s)
                    nil))
                #(download url))]
      (when resp
        (msg/publish qn (str "Successfully downloaded " url))))
    (catch Throwable e
      (msg/publish qn (str "Unable to download: " url ", reason: " e)))))

(defn metube-handler
  "Handler for enqueuing youtube download requests"
  [request]
  (try
    (let [url (slurp (reader (:body request)))]
      (if (and (string? url) (not (empty? url)))
        (do
          (msg/publish qn (str "Enqueuing download of " url))
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
