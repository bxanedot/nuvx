use super::*;

impl Backend {
    pub(super) async fn explore(&self, preferred_artists: &[String], me: bool) -> anyhow::Result<Resp> {
        let api = &*self.api;
        let (charts, new) = tokio::join!(ytm::charts(api), ytm::new_albums(api, me));
        let charts = charts?;
        Ok(Resp::Explore(Explore {
            new_albums: popular_new_albums(&new.unwrap_or_default(), &charts.artists, preferred_artists),
            charts: charts.playlists,
            top: charts.top,
        }))
    }

    pub(super) async fn search(&self, query: String, me: bool) -> anyhow::Result<Resp> {
        let api = &*self.api;
        let (t, al, ar, pl) = tokio::join!(
            ytm::search_songs(api, &query, me),
            ytm::search_cards(api, &query, ytm::Filter::Albums, me),
            ytm::search_cards(api, &query, ytm::Filter::Artists, me),
            ytm::search_cards(api, &query, ytm::Filter::Playlists, me),
        );
        Ok(Resp::Search(SearchResults {
            tracks: t?,
            albums: al.unwrap_or_default(),
            artists: ar.unwrap_or_default(),
            playlists: pl.unwrap_or_default(),
            query,
        }))
    }

    pub(super) async fn library(&self) -> anyhow::Result<Resp> {
        let api = &*self.api;
        let (pls, albums) = tokio::join!(ytm::saved_playlists(api), ytm::saved_albums(api));
        let pls = match (pls, &albums) {
            (Err(e), Err(_)) => return Err(e),
            (pls, _) => pls,
        };
        if let Err(e) = &pls {
            log::warn!("saved playlists didn't load: {e:#}");
        }
        if let Err(e) = &albums {
            log::warn!("saved albums didn't load: {e:#}");
        }
        Ok(Resp::Library(Library {
            playlists_failed: pls.is_err(),
            albums_failed: albums.is_err(),
            playlists: pls.map(|p| p.into_iter().filter(saved_playlist_visible).collect()).unwrap_or_default(),
            albums: albums.unwrap_or_default(),
        }))
    }

    pub(super) async fn upload(&self, files: &[PathBuf]) -> anyhow::Result<Resp> {
        let api = &*self.api;
        let session = api.account().ok_or_else(|| anyhow::anyhow!("sign in to upload music"))?;
        let (mut done, mut failed) = (0, Vec::new());
        for f in files {
            let name = f.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            match session.upload(&self.http(), f).await {
                Ok(()) => done += 1,
                Err(e) => {
                    log::warn!("upload {name}: {e:#}");
                    failed.push(name);
                }
            }
        }
        if done > 0 {
            let this = self.clone();
            self.rt.spawn(async move {
                for wait in [20, 40, 60] {
                    tokio::time::sleep(std::time::Duration::from_secs(wait)).await;
                    if let Ok(t) = ytm::uploads(&this.api).await {
                        let _ = this.tx.send(Resp::Uploads(t));
                        this.wake();
                    }
                }
            });
        }
        Ok(Resp::Uploaded { done, failed })
    }
}
