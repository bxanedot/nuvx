use super::*;

impl Transfer {
    pub fn set_cookie(self: &Arc<Self>, sp_dc: Option<String>, rt: &crate::backend::Backend) {
        match sp_dc {
            Some(c) => {
                if let Err(e) = crate::vault::set(VAULT, &self.data_dir, c.as_bytes()) {
                    *self.account.lock().unwrap() = Account::Failed(format!("Couldn't save the Spotify sign-in: {e}"));
                    return;
                }
                self.load_account(rt);
            }
            None => {
                self.account_gen.fetch_add(1, Ordering::Relaxed);
                crate::vault::delete(VAULT, &self.data_dir);
                crate::login::forget(&self.spotify_profile());
                *self.account.lock().unwrap() = Account::SignedOut;
            }
        }
    }

    pub fn spotify_profile(&self) -> PathBuf {
        self.data_dir.join("webview-spotify")
    }

    pub(super) fn cookie(&self) -> Option<String> {
        crate::vault::get(VAULT, &self.data_dir).and_then(|b| String::from_utf8(b).ok())
    }

    pub fn load_account(self: &Arc<Self>, rt: &crate::backend::Backend) {
        let Some(cookie) = self.cookie() else {
            *self.account.lock().unwrap() = Account::SignedOut;
            return;
        };
        *self.account.lock().unwrap() = Account::Loading;
        let generation = self.account_gen.fetch_add(1, Ordering::Relaxed) + 1;
        let this = self.clone();
        rt.spawn(async move {
            let res = async {
                let client = spotify::Client::connect(Some(&cookie), &this.data_dir).await?;
                client.account().await
            }
            .await;
            let mut account = this.account.lock().unwrap();
            if this.account_gen.load(Ordering::Relaxed) != generation {
                return;
            }
            *account = match res {
                Ok(a) => Account::SignedIn { name: a.name, library: a.library },
                Err(e) if e.is::<spotify::SignedOut>() => {
                    crate::vault::delete(VAULT, &this.data_dir);
                    Account::Failed(e.to_string())
                }
                Err(e) => {
                    log::warn!("spotify account: {e:#}");
                    Account::Failed(format!("Couldn't read your Spotify library: {e}"))
                }
            };
            drop(account);
            this.wake();
        });
    }

    pub fn ensure_account(self: &Arc<Self>, rt: &crate::backend::Backend) {
        if matches!(*self.account.lock().unwrap(), Account::Unknown) {
            self.load_account(rt);
        }
    }

    pub fn look_up(self: &Arc<Self>, rt: &crate::backend::Backend, link: &str) {
        let link = link.trim().to_owned();
        {
            let mut slot = self.lookup.lock().unwrap();
            if slot.as_ref().is_some_and(|(l, _)| *l == link) {
                return;
            }
            let source = match Source::parse(&link) {
                Ok(s) => s,
                Err(e) => {
                    *slot = (!link.is_empty()).then(|| (link, Some(Err(e.to_string()))));
                    return;
                }
            };
            if source == Source::Liked {
                let entry = spotify::Entry { source, name: "Liked Songs".into(), owner: String::new(), count: None, art: None };
                *slot = Some((link, Some(Ok(entry))));
                return;
            }
            *slot = Some((link.clone(), None));
        }
        let this = self.clone();
        rt.spawn(async move {
            let res = async {
                let Source::Playlist(id) = Source::parse(&link)? else { unreachable!() };
                let client = spotify::Client::connect(this.cookie().as_deref(), &this.data_dir).await?;
                client.playlist_info(&id).await
            }
            .await
            .map_err(|e| e.to_string());
            let mut slot = this.lookup.lock().unwrap();
            if slot.as_ref().is_some_and(|(l, _)| *l == link) {
                *slot = Some((link, Some(res)));
            }
            drop(slot);
            this.wake();
        });
    }
}
