;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.db :as db]
   [app.loggers.audit :as audit]
   [app.metrics :as mtx]
   [app.util.retry :as retry]
   [app.util.rlimit :as rlimit]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(defn- default-handler
  [_]
  (ex/raise :type :not-found))

(defn- run-hook
  [hook-fn response]
  (ex/ignoring (hook-fn))
  response)

(defn- rpc-query-handler
  [methods {:keys [profile-id session-id] :as request}]
  (let [type   (keyword (get-in request [:path-params :type]))

        data   (merge (:params request)
                      (:body-params request)
                      (:uploads request)
                      {::request request})

        data   (if profile-id
                 (assoc data :profile-id profile-id ::session-id session-id)
                 (dissoc data :profile-id))

        result ((get methods type default-handler) data)
        mdata  (meta result)]

    (cond->> {:status 200 :body result}
      (fn? (:transform-response mdata))
      ((:transform-response mdata) request))))

(defn- rpc-mutation-handler
  [methods {:keys [profile-id session-id] :as request}]
  (let [type   (keyword (get-in request [:path-params :type]))
        data   (merge (:params request)
                      (:body-params request)
                      (:uploads request)
                      {::request request})

        data   (if profile-id
                 (assoc data :profile-id profile-id ::session-id session-id)
                 (dissoc data :profile-id))

        result ((get methods type default-handler) data)
        mdata  (meta result)]
    (cond->> {:status 200 :body result}
      (fn? (:transform-response mdata))
      ((:transform-response mdata) request)

      (fn? (:before-complete mdata))
      (run-hook (:before-complete mdata)))))

(defn- wrap-with-metrics
  [cfg f mdata]
  (mtx/wrap-summary f (::mobj cfg) [(::sv/name mdata)]))

(defn- wrap-impl
  [{:keys [audit] :as cfg} f mdata]
  (let [f     (as-> f $
                (rlimit/wrap-rlimit cfg $ mdata)
                (retry/wrap-retry cfg $ mdata)
                (wrap-with-metrics cfg $ mdata))

        spec  (or (::sv/spec mdata) (s/spec any?))
        auth? (:auth mdata true)]

    (l/trace :action "register" :name (::sv/name mdata))
    (with-meta
      (fn [params]
        ;; Raise authentication error when rpc method requires auth but
        ;; no profile-id is found in the request.
        (when (and auth? (not (uuid? (:profile-id params))))
          (ex/raise :type :authentication
                    :code :authentication-required
                    :hint "authentication required for this endpoint"))

        (let [params' (dissoc params ::request)
              params' (us/conform spec params')
              result  (f cfg params')]

          ;; When audit log is enabled (default false).
          (when (fn? audit)
            (let [resultm    (meta result)
                  request    (::request params)
                  profile-id (or (:profile-id params')
                                 (:profile-id result)
                                 (::audit/profile-id resultm))
                  props      (d/merge params' (::audit/props resultm))]
              (audit :cmd :submit
                     :type (or (::audit/type resultm)
                               (::type cfg))
                     :name (or (::audit/name resultm)
                               (::sv/name mdata))
                     :profile-id profile-id
                     :ip-addr (audit/parse-client-ip request)
                     :props props)))

          result))
      mdata)))

(defn- process-method
  [cfg vfn]
  (let [mdata (meta vfn)]
    [(keyword (::sv/name mdata))
     (wrap-impl cfg (deref vfn) mdata)]))

(defn- resolve-query-methods
  [cfg]
  (let [mobj (mtx/create
              {:name "rpc_query_timing"
               :labels ["name"]
               :registry (get-in cfg [:metrics :registry])
               :type :histogram
               :help "Timing of query services."})
        cfg  (assoc cfg ::mobj mobj ::type "query")]
    (->> (sv/scan-ns 'app.rpc.queries.projects
                     'app.rpc.queries.files
                     'app.rpc.queries.teams
                     'app.rpc.queries.comments
                     'app.rpc.queries.profile
                     'app.rpc.queries.viewer
                     'app.rpc.queries.fonts)
         (map (partial process-method cfg))
         (into {}))))

(defn- resolve-mutation-methods
  [cfg]
  (let [mobj (mtx/create
              {:name "rpc_mutation_timing"
               :labels ["name"]
               :registry (get-in cfg [:metrics :registry])
               :type :histogram
               :help "Timing of mutation services."})
        cfg  (assoc cfg ::mobj mobj ::type "mutation")]
    (->> (sv/scan-ns 'app.rpc.mutations.demo
                     'app.rpc.mutations.media
                     'app.rpc.mutations.profile
                     'app.rpc.mutations.files
                     'app.rpc.mutations.comments
                     'app.rpc.mutations.projects
                     'app.rpc.mutations.teams
                     'app.rpc.mutations.management
                     'app.rpc.mutations.ldap
                     'app.rpc.mutations.fonts
                     'app.rpc.mutations.share-link
                     'app.rpc.mutations.verify-token)
         (map (partial process-method cfg))
         (into {}))))

(s/def ::storage some?)
(s/def ::session map?)
(s/def ::tokens fn?)
(s/def ::audit (s/nilable fn?))

(defmethod ig/pre-init-spec ::rpc [_]
  (s/keys :req-un [::storage ::session ::tokens ::audit
                   ::mtx/metrics ::db/pool]))

(defmethod ig/init-key ::rpc
  [_ cfg]
  (let [mq (resolve-query-methods cfg)
        mm (resolve-mutation-methods cfg)]
    {:methods {:query mq :mutation mm}
     :query-handler #(rpc-query-handler mq %)
     :mutation-handler #(rpc-mutation-handler mm %)}))
