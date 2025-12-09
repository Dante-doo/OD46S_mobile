package br.edu.utfpr.coletapb.utils

import android.util.Log
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * Tile source customizado para OpenStreetMap
 * O OSMDroid faz o download automaticamente usando a URL retornada
 */
class CustomTileSource(
    name: String,
    minZoom: Int,
    maxZoom: Int,
    tileSizePixels: Int,
    imageFilenameEnding: String,
    baseUrl: String
) : OnlineTileSourceBase(name, minZoom, maxZoom, tileSizePixels, imageFilenameEnding, arrayOf(baseUrl)) {
    
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        val url = baseUrl.replace("{z}", zoom.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        Log.d("CustomTileSource", "URL do tile: $url")
        return url
    }
    
    companion object {
        /**
         * Cria uma fonte de tiles do OpenStreetMap usando HTTPS
         * O network_security_config.xml deve estar configurado para aceitar certificados SSL
         */
        fun createOpenStreetMapHttps(): CustomTileSource {
            return CustomTileSource(
                name = "OpenStreetMap",
                minZoom = 1,
                maxZoom = 19,
                tileSizePixels = 256,
                imageFilenameEnding = ".png",
                baseUrl = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
            )
        }
        
        /**
         * Cria uma fonte de tiles alternativa usando CartoDB (também OpenStreetMap)
         * Pode funcionar melhor em alguns casos
         */
        fun createCartoDB(): CustomTileSource {
            return CustomTileSource(
                name = "CartoDB",
                minZoom = 1,
                maxZoom = 19,
                tileSizePixels = 256,
                imageFilenameEnding = ".png",
                baseUrl = "https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png"
            )
        }
    }
}

