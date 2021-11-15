package spp.probe.services.common.model

import java.io.Serializable

class LocalVariable(
    val name: String, val desc: String, val start: Int, val end: Int, val index: Int
) : Serializable {

    override fun toString(): String {
        return "LocalVariable{" +
                "name='" + name + '\'' +
                ", desc='" + desc + '\'' +
                ", start=" + start +
                ", end=" + end +
                ", index=" + index +
                '}'
    }
}
