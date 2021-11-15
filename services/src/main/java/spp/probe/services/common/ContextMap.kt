package spp.probe.services.common

class ContextMap {

    var localVariables: Map<String, Any>? = null
    var fields: Map<String, Any>? = null
    var staticFields: Map<String, Any>? = null

    override fun toString(): String {
        return "ContextMap{" +
                "localVariables=" + localVariables +
                ", fields=" + fields +
                ", staticFields=" + staticFields +
                '}'
    }
}
