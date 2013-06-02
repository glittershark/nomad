(ns nomad-test
  (:require [nomad :refer [defconfig]]
            [clojure.test :as test :refer [deftest]]
            [clojure.java.io :as io]))

(defmacro with-hostname [hostname & body]
  `(with-redefs [nomad/get-hostname (constantly ~hostname)]
     ~@body))

(defmacro with-instance [instance & body]
  `(with-redefs [nomad/get-instance (constantly ~instance)]
     ~@body))

(defrecord DummyConfigFile [_etag _content]
  nomad/ConfigFile
  (etag [_] (_etag))
  (slurp* [_] (_content)))

(deftest loads-simple-config
  (let [config {:my-key :my-val}
        {:keys [etag config]} (#'nomad/update-config-file
                               {:config-file (DummyConfigFile.
                                              (constantly :etag)
                                              (constantly (pr-str config)))})]
    (test/is (= :my-val (:my-key config)))
    (test/is (= :etag etag))))

(deftest caches-when-not-changed
  (let [config {:a 1 :b 2}
        constant-etag "the-etag"
        dummy-config-file (DummyConfigFile. (constantly constant-etag)
                                            #(throw (AssertionError.
                                                     "Shouldn't reload!")))
        returned-config (#'nomad/update-config-file
                         {:config-file dummy-config-file
                          :etag constant-etag
                          :config config})]
    (test/is (= config (select-keys (:config returned-config) [:a :b])))))

(deftest reloads-when-changed
  (let [old-config {:a 1 :b 2}
        new-config (assoc old-config :a 3)
        dummy-config-file (DummyConfigFile. (constantly "new-etag")
                                            (constantly (pr-str new-config)))
        returned-config (#'nomad/update-config-file
                         {:etag "old-etag"
                          :config-file dummy-config-file
                          :config old-config})]
    (test/is (= new-config (select-keys (:config returned-config) [:a :b])))))

(deftest host-config
  (let [config {:nomad/hosts {"my-host" {:a 1}
                              "not-my-host" {:a 2}}}
        dummy-config-file (DummyConfigFile. (constantly ::etag)
                                            (constantly
                                             (pr-str config)))
        returned-config (with-hostname "my-host"
                          (#'nomad/update-config
                           {:public {:config-file dummy-config-file}}))]
    (test/is (= 1 (get-in returned-config [:host :config :a])))))

(deftest instance-config
  (let [config {:nomad/hosts {"my-host"
                              {:nomad/instances
                               {"DEV1" {:a 1}
                                "DEV2" {:a 2}}}
                              "not-my-host" {:a 2}}}
        dummy-config-file (DummyConfigFile. (constantly ::etag)
                                            (constantly
                                             (pr-str config)))
        returned-config (with-hostname "my-host"
                          (with-instance "DEV2"
                            (#'nomad/update-config
                             {:public {:config-file dummy-config-file}})))]
    (test/is (= 2 (get-in returned-config [:instance :config :a])))))

(deftest merges-public-config
  (let [config-map {:public
                    {:config {:a :public
                              :b {:b1 :public
                                  :b2 :public
                                  :b3 :public}
                              :c :public
                              :d :public}}

                    :host
                    {:config {:c :host
                              :b {:b2 :host
                                  :b3 :host}}}

                    :instance
                    {:config {:d :instance
                              :b {:b3 :instance}}}

                    :environment
                    {:nomad/hostname :hostname
                     :nomad/instance :instance-name}}
        merged-config (#'nomad/merge-configs config-map)]
    (test/is (= :public (get-in merged-config [:a])))
    (test/is (= :public (get-in merged-config [:b :b1])))
    (test/is (= :host (get-in merged-config [:b :b2])))
    (test/is (= :instance (get-in merged-config [:b :b3])))
    (test/is (= :host (get-in merged-config [:c])))
    (test/is (= :instance (get-in merged-config [:d])))
    (test/is (= :hostname (get-in merged-config [:nomad/hostname])))
    (test/is (= :instance-name (get-in merged-config [:nomad/instance])))))

(deftest adds-hostname-key-to-map
    (let [returned-config (with-hostname "dummy-hostname"
                            (#'nomad/update-config
                             {:public {:config-file (DummyConfigFile.
                                                     (constantly ::etag)
                                                     (constantly (pr-str {})))}}))]
      (test/is (= "dummy-hostname" (get-in returned-config [:environment :nomad/hostname])))))

(deftest adds-instance-key-to-map
  (let [returned-config (with-instance "dummy-instance"
                          (#'nomad/update-config
                           {:public {:config-file (DummyConfigFile.
                                                   (constantly ::etag)
                                                   (constantly (pr-str {})))}}))]
    (test/is (= "dummy-instance" (get-in returned-config [:environment :nomad/instance])))))

(comment
  (defrecord DummyPrivateFile [etag* content*]
    nomad/ConfigFile
    (etag [_] etag*)
    (slurp* [_] (pr-str content*)))

  (deftest loads-private-config
    (let [config {:nomad/private-file
                  (DummyPrivateFile.
                   ::etag {:something-private :yes-indeed})}
          returned-config (#'nomad/update-config-pair
                           (with-meta config
                             {:old-public
                              (with-meta config
                                {:config-file (DummyConfigFile. (constantly ::etag)
                                                                (constantly
                                                                 (pr-str config)))})}))]
      (test/is (= :yes-indeed (get-in returned-config [:something-private])))))

  (deftest deep-merges-private-config
    (let [config {:database {:username "my-user"
                             :password "failed..."}
                  :nomad/private-file
                  (DummyPrivateFile.
                   ::etag {:database {:password "password123"}})}
          returned-config (#'nomad/update-config-pair
                           (with-meta config
                             {:old-public (with-meta config
                                            {:config-file (DummyConfigFile. (constantly ::etag)
                                                                            (constantly
                                                                             (pr-str config)))})}))]
      (test/is (= "my-user" (get-in returned-config [:database :username]))) 
      (test/is (= "password123" (get-in returned-config [:database :password])))))

  (deftest reloads-private-config-when-private-file-changes
    (let [new-private-file (DummyPrivateFile.
                            "new-etag" {:host-private :yes-indeed})
          new-config {:nomad/private-file new-private-file}
          returned-config (#'nomad/update-config-pair
                           (with-meta {:nomad/private-file new-private-file
                                       :host-private :definitely-not}
                             {:old-public {:nomad/private-file new-private-file}
                              :old-private (with-meta {:host-private :definitely-not}
                                             {:config-file new-private-file
                                              :old-etag "old-etag"})}))]
      
      (test/is (= :yes-indeed (get-in returned-config [:host-private])))))

  (defrecord DummyUnchangingPrivateFile [etag*]
    nomad/ConfigFile
    (etag [_] etag*)
    (slurp* [_] (throw (AssertionError. "Shouldn't reload!"))))

  (deftest caches-private-config-when-nothing-changes
    (let [private-file (DummyUnchangingPrivateFile. "same-private-etag")
          config {:nomad/private-file private-file}
          returned-config (#'nomad/update-config-pair
                           (with-meta {:nomad/private-file private-file
                                       :host-private :yes-indeed}
                             {:old-public {:nomad/private-file private-file}
                              :old-private (with-meta {:host-private :yes-indeed}
                                             {:config-file private-file
                                              :old-etag "same-private-etag"})}))]
      (test/is (= :yes-indeed (get-in returned-config [:host-private])))))

  )

(comment
  ;; This bit is for some manual integration testing

  (defconfig test-config (io/resource "test-config.edn"))

  (with-hostname "my-host"
    (with-instance "test-instance"
      (test-config))))
