#!/usr/bin/env bb

(require '[cheshire.core :as json]
         '[babashka.cli :as cli]
         '[babashka.http-client :as http])

(def cloud-init (str "
#cloud-config
packages:
  - tailscale
  - git
runcmd:
  - systemctl enable --now tailscaled
  - tailscale up --authkey " (System/getenv "TAILSCALE_KEY")))

(defn base64-encode [s]
  (->> s
       .getBytes
       (.encodeToString (java.util.Base64/getEncoder))))

(defn create-server [server-name]
  (if-not server-name
    (println "Usage: cloud create <name>")
    (-> (http/post "https://api.hetzner.cloud/v1/servers"
                   {:headers {"Authorization" (str "Bearer " (System/getenv "HETZNER_TOKEN"))
                              "Content-Type" "application/json"}
                    :body (json/generate-string
                           {:name server-name
                            :server_type "cpx11"
                            :image "fedora-41"
                            :ssh_keys ["rob@Roberts-MacBook-Pro.local"]
                            :user_data (base64-encode cloud-init)
                            :location "hil"
                            :firewalls [{:firewall 1676272}]})})
        :body
        (json/parse-string true)
        (json/generate-string {:pretty true})
        println)))

(defn delete-server [server-name]
  (let [servers (-> (http/get "https://api.hetzner.cloud/v1/servers"
                              {:headers {"Authorization" (str "Bearer " (System/getenv "HETZNER_TOKEN"))
                                         "Content-Type" "application/json"}})
                    :body
                    (json/parse-string true)
                    :servers)
        name->id (into {} (map (juxt :name :id) servers))
        id (name->id server-name)
        devices (-> (http/get "https://api.tailscale.com/api/v2/tailnet/-/devices"
                             {:headers {"Authorization" (str "Bearer " (System/getenv "TAILSCALE_API_KEY"))}})
                    :body
                    (json/parse-string true)
                    :devices)
        hostname->id (into {} (map (juxt :hostname :nodeId) devices))
        nodeid (hostname->id server-name)]
    (http/delete (str "https://api.hetzner.cloud/v1/servers/" id)
                 {:headers {"Authorization" (str "Bearer " (System/getenv "HETZNER_TOKEN"))
                            "Content-Type" "application/json"}})
    (http/delete (str "https://api.tailscale.com/api/v2/device/" nodeid)
                 {:headers {"Authorization" (str "Bearer " (System/getenv "TAILSCALE_API_KEY"))}})))

(defn get-servers []
  (let [servers (-> (http/get "https://api.hetzner.cloud/v1/servers"
                              {:headers {"Authorization" (str "Bearer " (System/getenv "HETZNER_TOKEN"))
                                         "Content-Type" "application/json"}})
                   :body
                   (json/parse-string true)
                   :servers)]
    (doseq [server servers]
      (println (:name server)))))

(defn -main [args]
  (let [[subcommand arg1] (-> args cli/parse-args :args)]
    (case subcommand
      "create" (create-server arg1)
      "delete" (delete-server arg1)
      "list" (get-servers)
      (println "Usage: cloud create <name>"))))

(comment
  (-main ["create"])
  (create-server "hetzner2")
  (delete-server "hetzner")
  (get-servers))

(-main *command-line-args*)
