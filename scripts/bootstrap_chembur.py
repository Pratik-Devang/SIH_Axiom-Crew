"""
bootstrap_chembur.py
====================
Download the Chembur, Mumbai 1km OSM road network and save it as:
  data/chembur_1km.graphml
  data/chembur_1km_nodes.parquet
  data/chembur_1km_edges.parquet

Run this once before running the test suite.

Usage
-----
  python scripts/bootstrap_chembur.py

Requirements
------------
  pip install osmnx networkx geopandas pyarrow pandas
"""

import logging
import sys
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logger = logging.getLogger(__name__)

# Add repo root to path so imports work from scripts/
ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(ROOT))


def main() -> None:
    try:
        import osmnx as ox
    except ImportError:
        logger.error("osmnx not installed. Run: pip install osmnx pyarrow")
        sys.exit(1)

    data_dir = ROOT / "data"
    data_dir.mkdir(exist_ok=True)

    graphml_path = data_dir / "chembur_1km.graphml"
    nodes_path = data_dir / "chembur_1km_nodes.parquet"
    edges_path = data_dir / "chembur_1km_edges.parquet"

    if graphml_path.exists():
        logger.info("GraphML already exists at %s — skipping download.", graphml_path)
        logger.info("Delete the file and re-run to force a fresh download.")
    else:
        center_point = (19.051, 72.894)  # Chembur, Mumbai
        radius_m = 1000

        logger.info(
            "Downloading Chembur OSM graph (centre=%s, r=%dm) …",
            center_point, radius_m,
        )
        ox.settings.log_console = False
        ox.settings.use_cache = True

        G = ox.graph_from_point(center_point, dist=radius_m, network_type="drive")
        logger.info(
            "Downloaded: %d nodes, %d edges",
            G.number_of_nodes(), G.number_of_edges(),
        )

        ox.save_graphml(G, str(graphml_path))
        logger.info("Saved GraphML → %s", graphml_path)

        nodes, edges = ox.graph_to_gdfs(G)

        # osmnx edges can have mixed list/scalar 'osmid' column
        # (one edge may reference multiple OSM way IDs).  pyarrow cannot
        # serialise mixed object columns — stringify them first.
        for col in edges.columns:
            if edges[col].dtype == object:
                edges[col] = edges[col].astype(str)
        for col in nodes.columns:
            if nodes[col].dtype == object:
                nodes[col] = nodes[col].astype(str)

        nodes.to_parquet(str(nodes_path))
        edges.to_parquet(str(edges_path))
        logger.info("Saved nodes → %s", nodes_path)
        logger.info("Saved edges → %s", edges_path)

    # Quick sanity-check: load the graph and print stats
    logger.info("Verifying graph load …")
    import osmnx as ox
    G = ox.load_graphml(str(graphml_path))
    logger.info(
        "✓ Graph loaded OK: %d nodes, %d edges",
        G.number_of_nodes(), G.number_of_edges(),
    )
    logger.info("Bootstrap complete. Run: pytest tests/ -v")


if __name__ == "__main__":
    main()
