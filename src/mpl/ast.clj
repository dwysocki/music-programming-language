(ns mpl.ast
  "A collection of functions for transforming the ANTLR AST into a more
  desirable AST, as well as functions to operate on the transformed AST."
  (:require [clojure.reflect :refer [typename]]
            [mpl.util :as util])
  (:import [org.antlr.v4.runtime.tree TerminalNodeImpl]
           [mpl.antlr MPLParser]))

(defn validate-tone
  "Return the tone if it is valid (a-gA-G) and throw an exception if not."
  [tone]
  tone)

(def ^:private parser-inner-classes
  "An array of all inner classes of MiniJavaParser."
  (.getClasses MPLParser))


(defn- typeify
  "Transform the given type generated by ANTLR into a clojure keyword.

  For example, if given the type ClassDeclarationContext, outputs
  :class-declaration."
  [type]
  (let [str-name (-> type
                     typename
                     (clojure.string/replace #".*MPLParser\$" "")
                     (clojure.string/replace #"Context" "")
                     util/camel->lisp)
        kw-name  (keyword str-name)]
    [kw-name type]))


(def ^:private type->key
  "A mapping from MPLParser inner class types to their keyword
  representations. TerminalNodeImpl -> :terminal-node is added manually,
  as it is the only type not ending with \"Context\" which needs to be used."
  (assoc (into {} (map (comp vec reverse typeify) parser-inner-classes))
    TerminalNodeImpl :terminal-node))


(def ^:private obj->type-key
  "A mapping from objects to their type keywords, as given by type->key."
  (comp type->key type))

(defn- children
  "Returns all children of a given node."
  [node]
  (map #(.getChild node %) (range (.getChildCount node))))

(defmulti ast obj->type-key)

(defmethod ast :default [node]
  node)

(defmethod ast :terminal-node [node]
  "Reached a terminal node, simply transform it into the underlying text of
  its symbol."
  (-> node .-symbol .getText))

(defmethod ast :goal [node]
  (let [children (children node)]
   {:meta (ast (first children))
    :body (ast (second children))}))

(defmethod ast :meta [node]
  (into {} (map ast (children node))))

(defmethod ast :attribute [node]
  (let [[key _ val _] (children node)]
    [(ast key)
     (ast val)]))

(defmethod ast :attr-key [node]
  (ast (.getChild node 0)))

(defmethod ast :attr-val [node]
  (ast (.getChild node 0)))

(defmethod ast :body [node]
  (map ast (children node)))

(defmethod ast :expr [node]
  (ast (.getChild node 0)))

(defmethod ast :measure [node]
  {:type :measure
   :notes (-> node
              ;; get the node's children
              children
              ;; take only the 2nd child (it's the vector of notes)
              first ;;second
              ;; first instead of second once we remove bars
              ;; turn into an ast
              ast)})

(defmethod ast :repeat [node]
  {:type :repeat
   :exprs (->> node
               ;; get the node's children
               children
               ;; drop the first and last children (they're braces)
               rest butlast
               ;; turn into an ast
               (map ast))})

(defmethod ast :notes [node]
  (->> node
       ;; take the node's children
       children
       ;; build their ast's
       (map ast)))

(defmethod ast :note [node]
  (let [[accidentals tone octaves] (children node)]
    {:tone       (-> tone
                     ;; parse the tone
                     ast
                     ;; check that it is a valid tone
                     validate-tone),
     :accidental (ast accidentals),
     :octave     (ast octaves)}))

(defmethod ast :accidentals [node]
  (let [children (children node)]
    (reduce + (map ast children))))


(defmethod ast :octaves [node]
  (let [children (children node)]
   (reduce + (map ast children))))

(defmethod ast :sharp [node]
  +1)

(defmethod ast :flat [node]
  -1)

(defmethod ast :up-octave [node]
  +1)

(defmethod ast :down-octave [node]
  -1)


(defn cmd-list
  [tree]
  (let [clear-measure #(if (= (:type %) :measure) (:notes %) %)]
    (map #(if (= (:type %) :repeat) (map clear-measure (:exprs %)) (clear-measure %)) (tree :body))))
