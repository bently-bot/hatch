package dev.hatch

import dev.hatch.guest.RootfsCatalog
import org.junit.Assert.*
import org.junit.Test

class RootfsDockerTest {
    private val index = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[
      {"digest":"sha256:aaa","platform":{"architecture":"amd64","os":"linux"}},
      {"digest":"sha256:att","platform":{"architecture":"unknown","os":"unknown"}},
      {"digest":"sha256:v7","platform":{"architecture":"arm","os":"linux","variant":"v7"}},
      {"digest":"sha256:bbb","platform":{"architecture":"arm64","os":"linux","variant":"v8"}}]}"""

    @Test fun picksManifestPerArch() {
        assertEquals("sha256:bbb", RootfsCatalog.manifestDigest(index, "arm64"))
        assertEquals("sha256:aaa", RootfsCatalog.manifestDigest(index, "amd64"))
    }

    @Test fun singleLayer() {
        val m = """{"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"sha256:229b","size":49761602}]}"""
        assertEquals("sha256:229b", RootfsCatalog.layerDigest(m))
        assertNull(RootfsCatalog.layerDigest("""{"layers":[{"digest":"a"},{"digest":"b"}]}"""))
    }

}
