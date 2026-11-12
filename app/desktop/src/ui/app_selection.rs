use super::Page;

#[derive(Default)]
pub struct Selection {
    pub page: Option<Page>,
    pub rows: std::collections::BTreeSet<usize>,
    pub anchor: Option<usize>,
    pub active: bool,
}

impl Selection {
    pub fn on(&self, page: &Page) -> bool {
        self.page.as_ref() == Some(page) && (self.active || !self.rows.is_empty())
    }

    pub fn clear(&mut self) {
        *self = Self::default();
    }

    pub fn start(&mut self, page: &Page) {
        *self = Self { page: Some(page.clone()), active: true, ..Self::default() };
    }

    pub fn pick(&mut self, page: &Page, i: usize, range: bool) {
        if self.page.as_ref() != Some(page) {
            *self = Self { page: Some(page.clone()), ..Self::default() };
        }
        match self.anchor.filter(|_| range) {
            Some(a) => self.rows.extend(a.min(i)..=a.max(i)),
            None => {
                if !self.rows.remove(&i) {
                    self.rows.insert(i);
                }
                self.anchor = Some(i);
            }
        }
    }
}
