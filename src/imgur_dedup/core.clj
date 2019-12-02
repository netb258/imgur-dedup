(ns imgur-dedup.core
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as s])
  (:import [ImagePHash])
  (:gen-class))

;; ------------------------------------------------------------------------------------------------------------- 
;; -------------------------------------------- IMGUR API HANDING ----------------------------------------------
;; ------------------------------------------------------------------------------------------------------------- 

(def client-id (first *command-line-args*))
(def album-id (second *command-line-args*))
(def album-data-url (str "https://api.imgur.com/3/album/" album-id))
(def album-images-data-url (str "https://api.imgur.com/3/album/" album-id "/images"))
(def oauth-header {:headers {"Authorization" (str "Client-ID " client-id)}})

(defn get-json [url header]
  (json/read-str
    (:body (client/get url header))))

(def album-data-json
  (get-json album-data-url oauth-header))

(def album-name (get-in album-data-json ["data" "title"]))

(def album-images-json
  (get-json album-images-data-url oauth-header))

(defn get-album-links [images-json]
  (for [image-data (get images-json "data")
        :let [link (get image-data "link")]]
    link))

;; ------------------------------------------------------------------------------------------------------------- 
;; ------------------------------------------- PROCESSING FUNCTIONS --------------------------------------------
;; ------------------------------------------------------------------------------------------------------------- 

(def links
  (get-album-links album-images-json))

(def num-links (count links))

(defn try-n-times
  "A simple function that calls another function (f) a number of times (n) in case an exception is thrown."
  [f n]
  (if (zero? n) ;; If (n) reached zero, then the below (f) will be our final attempt (base case).
    (f)
    (try ;; Otherwise call (f) in a try/catch block.
      (f) ;; If there is no exception, then this call to (f) will be the only returned result.
      (catch Throwable e ;; If we caught an exception, then we call try-n-times again with a decreased (n).
        (println (str "Caught exception: " (.getMessage e) " | retrying: " n))
        (try-n-times f (dec n))))))

(defn make-get
  "Makes an HTTP get request in a new thread.
  Note, that this function will retry the request five times before giving up with an Exception.
  Also there is a timeout of three seconds, after which an exception is thrown."
  [url options]
  (future
    (try-n-times #(client/get url (assoc options :socket-timeout 3000 :conn-timeout 3000)) 5)))

(defn get-all-images
  "Pretty much self explanatory. Will return a seq of links and their streams.
  Note, that we will ignore all cookies for each request."
  [img-links]
  (for [link img-links]
    [link (make-get link {:as :stream :client-params {:cookie-policy (constantly nil)}})]))

(defn get-phash
  "Returns the phash of an image."
  [img-stream]
  (.getHash (ImagePHash.) img-stream))

(defn get-similar-images
  "Takes an image link paired with a phash and compares it's similarity against a list of other links and phashes.
  Returns a map of links and hashes with similarity > 90%."
  [hash-pair other-hash-pairs]
  (into {}
        (filter
          #(> (ImagePHash/similarity (second hash-pair) (second %)) 0.90)
          other-hash-pairs)))

(defn make-phash-map
  "Given a list of links and image streams, this function returns a map with the links as keys and their phashes as values."
  [img-links]
  (into {} ;; Transform the seq from the next expression into a Clojure map.
        (map-indexed ;; This will transform the seq of links and img-streams from get-all-images, INTO a seq of links and phashes.
          (fn [idx pair]
            [(first pair)
             (try
               (println (str "Hashing - " (first pair)))
               (println (str "Progress: " (format "%.2f" (* (/ (double (inc idx)) (double num-links)) 100.0)) "%"))
               (get-phash (:body @(last pair))) ;; If we couldn't hash the img stream, then ImagePHash should throw an exception here.
               (catch Throwable e
                 (println)
                 (println (str "Could not process: " (first pair)))))]) ;; nil will be returned here as the result (no hash).
          (get-all-images img-links))))

(defn get-similarity-map
  "Takes a seq with pairs of image links and phashes and returns a map with all similar links.
  The format is: {link1: [ALL LINKS SIMILAR TO link1], link2: same... }.
  If an image is not similar to any other images, then it's link will contain an empty list, like so: {link1: '()}"
  ([hash-pairs] (get-similarity-map hash-pairs '()))
  ([hash-pairs acc]
   (if (empty? hash-pairs) acc
     (let [head (first hash-pairs)
           tail (into {} (rest hash-pairs))
           similar (keys (get-similar-images head tail))
           rest-without-similar (apply dissoc tail similar)]
       (recur rest-without-similar (conj acc [(first head) similar]))))))

;; ------------------------------------------------------------------------------------------------------------- 
;; ---------------------------------------------------- MAIN ---------------------------------------------------
;; ------------------------------------------------------------------------------------------------------------- 

(defn -main [& args]
  (def hashes-clean ;; The make-phash-map function may return nil for some links, if an exception is thrown (best exclude those).
    (filter #(not (nil? (get % 1))) (make-phash-map links)))

  (def similarity-map
    (filter ;; Remove images that are not similar to any other image (They should be paired with an empty list).
            #(not (empty? (get % 1))) (get-similarity-map (into {} hashes-clean))))

  ;; Turn the similarity map into a convenient HTML report.
  (def similarity-map-html
    (clojure.string/join
      (letfn [(get-post-url [img-url]
                (clojure.string/replace img-url #"\.jpg" ""))
              (make-link [img-url]
                (str "<a href='" (get-post-url img-url) "'><img src='" img-url "'/></a>"))]
        (for [sim similarity-map]
          (str "<div style='border: double'>"
               (make-link (first sim)) (clojure.string/join " " (map make-link (second sim))) "<br/>"
               "</div>")))))

  (spit (str album-name ".html") (str "<html><body>" similarity-map-html "</body></html>"))
  (System/exit 0))
