"""
osm_loader.py
=============
Load and cache OSM road-network graphs (osmnx-based).

Provides two load paths:
1. **Cached** — load a pre-downloaded ``.graphml`` file from disk.  Used
   during development and in CI to avoid network calls.
2. **Live** — pull fresh data from the OpenStreetMap Overpass API via
   ``osmnx``.  Used when a new geographic area (e.g. the real IO-VNBD
   route from Role 1) is provided.

Android export note (for Role 5)
---------------------------------
The ``networkx`` MultiDiGraph returned by this loader is **not**
Android-consumable.  Use :meth:`OSMLoader.export_lightweight_json` to
produce a trimmed JSON schema (nodes + edges + headings) suitable for
bundling in the APK or loading at runtime.

Role 1 integration note
------------------------
When Role 1 provides the real bounding box, call::

    loader.load_from_bbox(north, south, east, west)

and then re-export the fixture files to ``data/``.
"""

from __future__ import annotations

import json
import logging
import math
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import networkx as nx
import osmnx as ox
import yaml

logger = logging.getLogger(__name__)


def _load_config(config_path: str | Path = "configs/role4.yaml") -> dict:
    with open(config_path, "r") as f:
        return yaml.safe_load(f)


class OSMLoader:
    """Load, cache, and query OSM road-network graphs.

    Parameters
    ----------
    config_path : str or Path
        Path to ``configs/role4.yaml``.
    network_type : str
        osmnx network type filter.  ``"drive"`` for vehicle routing.

    Examples
    --------
    Load from cached file:

    >>> loader = OSMLoader()
    >>> G = loader.load_from_graphml("data/chembur_1km.graphml")

    Load live (requires network):

    >>> G = loader.load_from_point(19.051, 72.894, radius_m=1000)
    """

    def __init__(
        self,
        config_path: str | Path = "configs/role4.yaml",
        network_type: str = "drive",
    ) -> None:
        self._cfg = _load_config(config_path)
        self._network_type = network_type
        self._graph: Optional[nx.MultiDiGraph] = None

        # Configure osmnx
        ox.settings.log_console = False
        ox.settings.use_cache = True

        logger.debug("OSMLoader initialised, network_type=%s", network_type)

    # ------------------------------------------------------------------
    # Load paths
    # ------------------------------------------------------------------

    def load_from_graphml(self, path: str | Path) -> nx.MultiDiGraph:
        """Load graph from a cached ``.graphml`` file.

        Parameters
        ----------
        path : str or Path
            Path to the ``.graphml`` file (e.g. ``data/chembur_1km.graphml``).

        Returns
        -------
        nx.MultiDiGraph
            The loaded road network graph.

        Raises
        ------
        FileNotFoundError
            If the file does not exist.
        """
        path = Path(path)
        if not path.exists():
            raise FileNotFoundError(
                f"GraphML file not found: {path}. "
                "Run the bootstrap script to download it, or call "
                "load_from_point() to pull fresh data."
            )
        logger.info("Loading graph from %s", path)
        self._graph = ox.load_graphml(str(path))
        logger.info(
            "Graph loaded: %d nodes, %d edges",
            self._graph.number_of_nodes(),
            self._graph.number_of_edges(),
        )
        return self._graph

    def load_from_point(
        self,
        lat: float,
        lon: float,
        radius_m: float = 1000.0,
        save_path: Optional[str | Path] = None,
    ) -> nx.MultiDiGraph:
        """Pull graph from OSM centred on (lat, lon) with given radius.

        Parameters
        ----------
        lat : float
            Centre latitude.
        lon : float
            Centre longitude.
        radius_m : float
            Radius in metres.
        save_path : str or Path, optional
            If provided, saves the downloaded graph as GraphML at this path.

        Returns
        -------
        nx.MultiDiGraph
        """
        logger.info(
            "Pulling OSM graph from Overpass: centre=(%.4f, %.4f) r=%.0fm",
            lat, lon, radius_m,
        )
        self._graph = ox.graph_from_point(
            (lat, lon), dist=radius_m, network_type=self._network_type
        )
        logger.info(
            "Graph downloaded: %d nodes, %d edges",
            self._graph.number_of_nodes(),
            self._graph.number_of_edges(),
        )
        if save_path is not None:
            save_path = Path(save_path)
            save_path.parent.mkdir(parents=True, exist_ok=True)
            ox.save_graphml(self._graph, str(save_path))
            logger.info("Graph saved to %s", save_path)
        return self._graph

    def load_from_bbox(
        self,
        north: float,
        south: float,
        east: float,
        west: float,
        save_path: Optional[str | Path] = None,
    ) -> nx.MultiDiGraph:
        """Pull graph from OSM for a bounding box.

        Use this path when Role 1 provides the real IO-VNBD trip bounding box.

        Parameters
        ----------
        north, south, east, west : float
            Bounding box in decimal degrees.
        save_path : str or Path, optional
            If provided, saves the downloaded graph as GraphML.

        Returns
        -------
        nx.MultiDiGraph
        """
        logger.info(
            "Pulling OSM graph from bbox: N=%.4f S=%.4f E=%.4f W=%.4f",
            north, south, east, west,
        )
        self._graph = ox.graph_from_bbox(
            north, south, east, west, network_type=self._network_type
        )
        logger.info(
            "Graph downloaded: %d nodes, %d edges",
            self._graph.number_of_nodes(),
            self._graph.number_of_edges(),
        )
        if save_path is not None:
            save_path = Path(save_path)
            save_path.parent.mkdir(parents=True, exist_ok=True)
            ox.save_graphml(self._graph, str(save_path))
            logger.info("Graph saved to %s", save_path)
        return self._graph

    # ------------------------------------------------------------------
    # Query API
    # ------------------------------------------------------------------

    @property
    def graph(self) -> nx.MultiDiGraph:
        """The loaded NetworkX road graph.

        Raises
        ------
        RuntimeError
            If no graph has been loaded yet.
        """
        if self._graph is None:
            raise RuntimeError(
                "No graph loaded. Call load_from_graphml(), "
                "load_from_point(), or load_from_bbox() first."
            )
        return self._graph

    def get_node_coords(self) -> List[Tuple[int, float, float]]:
        """Return a list of (node_id, lat, lon) for all nodes.

        Returns
        -------
        list of (int, float, float)
        """
        return [
            (nid, data["y"], data["x"])
            for nid, data in self.graph.nodes(data=True)
        ]

    def get_edge_midpoints(self) -> List[Tuple[Any, Any, float, float, float]]:
        """Return edge midpoints as (u, v, mid_lat, mid_lon, heading_deg).

        Used by :class:`maps.candidates.CandidateGenerator` to build the
        KD-tree.  Heading is approximate (straight-line from u → v).

        Returns
        -------
        list of (u, v, mid_lat, mid_lon, heading_deg)
        """
        results = []
        for u, v, data in self.graph.edges(data=True):
            u_data = self.graph.nodes[u]
            v_data = self.graph.nodes[v]
            lat1, lon1 = u_data["y"], u_data["x"]
            lat2, lon2 = v_data["y"], v_data["x"]
            mid_lat = (lat1 + lat2) / 2.0
            mid_lon = (lon1 + lon2) / 2.0
            # Bearing from u to v
            dlon = math.radians(lon2 - lon1)
            phi1, phi2 = math.radians(lat1), math.radians(lat2)
            x = math.sin(dlon) * math.cos(phi2)
            y = (math.cos(phi1) * math.sin(phi2)
                 - math.sin(phi1) * math.cos(phi2) * math.cos(dlon))
            heading = (math.degrees(math.atan2(x, y)) + 360.0) % 360.0
            results.append((u, v, mid_lat, mid_lon, heading))
        return results

    # ------------------------------------------------------------------
    # Android / Role 5 export
    # ------------------------------------------------------------------

    def export_lightweight_json(
        self,
        output_path: str | Path,
        route_nodes: Optional[List[int]] = None,
    ) -> None:
        """Export a trimmed JSON graph for Android (Role 5).

        Produces a schema agreed with Role 5:

        .. code-block:: json

            {
              "nodes": [{"id": 123, "lat": 19.05, "lon": 72.89}, ...],
              "edges": [{"u": 123, "v": 456, "length_m": 85.2,
                         "heading_deg": 90.0, "name": "LBS Road"}, ...]
            }

        Parameters
        ----------
        output_path : str or Path
            Where to write the JSON file.
        route_nodes : list of int, optional
            If provided, only export the subgraph induced by these nodes
            (to limit file size for a known route corridor).
        """
        G = self.graph
        if route_nodes is not None:
            G = G.subgraph(route_nodes).copy()

        nodes_out = []
        for nid, data in G.nodes(data=True):
            nodes_out.append({
                "id": int(nid),
                "lat": float(data["y"]),
                "lon": float(data["x"]),
            })

        edges_out = []
        for u, v, data in G.edges(data=True):
            u_data = G.nodes[u]
            v_data = G.nodes[v]
            lat1, lon1 = u_data["y"], u_data["x"]
            lat2, lon2 = v_data["y"], v_data["x"]
            dlon = math.radians(lon2 - lon1)
            phi1, phi2 = math.radians(lat1), math.radians(lat2)
            x_b = math.sin(dlon) * math.cos(phi2)
            y_b = (math.cos(phi1) * math.sin(phi2)
                   - math.sin(phi1) * math.cos(phi2) * math.cos(dlon))
            heading = (math.degrees(math.atan2(x_b, y_b)) + 360.0) % 360.0

            edges_out.append({
                "u": int(u),
                "v": int(v),
                "length_m": float(data.get("length", 0.0)),
                "heading_deg": round(heading, 2),
                "name": str(data.get("name", "")),
                "highway": str(data.get("highway", "")),
                "oneway": bool(data.get("oneway", False)),
            })

        out = {"nodes": nodes_out, "edges": edges_out}
        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with open(output_path, "w") as f:
            json.dump(out, f, indent=2)
        logger.info(
            "Exported lightweight JSON: %d nodes, %d edges → %s",
            len(nodes_out), len(edges_out), output_path,
        )
