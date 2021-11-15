package spp.probe.services.common.model

import java.io.Serializable

class ClassField(val access: Int, val name: String, val desc: String) : Serializable {

    override fun toString(): String {
        return "ClassField{" +
                "access=" + access +
                ", name='" + name + '\'' +
                ", desc='" + desc + '\'' +
                '}'
    }
}
