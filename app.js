const PODCASTS_CONFIG = [
    {
        "title": "L'Heure des Pros",
        "url": "https://feeds.acast.com/public/shows/61308a906d38e90019a4bdd1",
        "image_url": "https://assets.pippa.io/shows/61308a906d38e90019a4bdd1/1648740942539-286bceb5bffbf0cf62681f5a814e437c.jpeg"
    },
    {
        "title": "LEGEND",
        "url": "https://feeds.acast.com/public/shows/646cbb0bb160e00011df3980",
        "image_url": "https://assets.pippa.io/shows/646cbb0bb160e00011df3980/1730108849125-446affaf-16be-4c2d-ab04-864b22a9a1b5.jpeg"
    },
    {
        "title": "Over n Out",
        "url": "https://feeds.acast.com/public/shows/over-n-out",
        "image_url": "https://assets.pippa.io/shows/624deb18ba967f0012b9d467/1754234136037-f3084da5-a15c-49be-ac3e-395dd00a32aa.jpeg"
    },
    {
        "title": "HVF - Histoires Vraies et Flippantes",
        "url": "https://feeds.acast.com/public/shows/6335d2ad559a970014e27ded",
        "image_url": "https://assets.pippa.io/shows/6335d2ad559a970014e27ded/1698139951939-599d33526922566930bce519f279d517.jpeg"
    },
    {
        "title": "Face à Philippe de Villiers",
        "url": "https://feeds.acast.com/public/shows/face-a-de-villiers",
        "image_url": "https://assets.pippa.io/shows/64edcb6245b5ac0011588018/1738050811249-8de9bd1a-f736-4e47-ae72-bc7116cbf5f8.jpeg"
    },
    {
        "title": "Renaud Dékode",
        "url": "https://feed.ausha.co/RdgJASVVxKke",
        "image_url": "https://image.ausha.co/BjAuUZWoKOusXcDRGJSNAbDNRvNZPC5qDNXZ3Mv6_1400x1400.jpeg?t=1729987591"
    },
    {
        "title": "TOCSIN PODCAST",
        "url": "https://feeds.acast.com/public/shows/65f86bd4cb784e0016bbc9f4",
        "image_url": "https://assets.pippa.io/shows/65f86bd4cb784e0016bbc9f4/1714404985146-bfd1b8903348f90bcf4963d1c255cf11.jpeg"
    },
    {
        "title": "L'Heure des Pros 2",
        "url": "https://feeds.acast.com/public/shows/lheure-des-pros-2",
        "image_url": "https://assets.pippa.io/shows/6156d7f1a603860012aee1d9/1648814874847-dc8ad689a4c84c38861d45e2bd3b9246.jpeg"
    },
    {
        "title": "GÉOPOLITIQUE PROFONDE",
        "url": "https://media.rss.com/geopolitique-profonde/feed.xml",
        "image_url": "https://media.rss.com/geopolitique-profonde/20260109_040154_2c2fe35f46c102cb8f326d8eb61d3b9246.png"
    }
];

class Podcatcher {
    constructor() {
        this.audio = document.getElementById('audio-player');
        this.podcastList = document.getElementById('podcast-list');
        this.episodeGrid = document.getElementById('episode-grid');
        this.btnPlay = document.getElementById('btn-play');
        this.progressFill = document.getElementById('progress-fill');
        this.progressContainer = document.getElementById('progress-container');
        this.timeCurrent = document.getElementById('time-current');
        this.timeTotal = document.getElementById('time-total');
        this.playerImg = document.getElementById('player-img');
        this.playerTitle = document.getElementById('player-title');
        this.playerAuthor = document.getElementById('player-author');
        this.sidebar = document.getElementById('sidebar');
        this.menuToggle = document.getElementById('menu-toggle');

        this.currentPodcast = null;
        this.currentEpisode = null;
        this.isPlaying = false;

        this.init();
    }

    init() {
        lucide.createIcons();
        this.renderPodcasts();
        this.setupEventListeners();
        this.setupMediaSession();
        this.registerServiceWorker();
    }

    registerServiceWorker() {
        if ('serviceWorker' in navigator) {
            navigator.serviceWorker.register('./sw.js')
                .then(reg => console.log('SW Registered', reg))
                .catch(err => console.log('SW Error', err));
        }
    }

    renderPodcasts() {
        this.podcastList.innerHTML = PODCASTS_CONFIG.map((p, index) => `
            <div class="podcast-item" onclick="app.loadPodcast(${index})">
                <img src="${p.image_url}" class="podcast-img" alt="${p.title}">
                <div class="podcast-info">
                    <div class="podcast-title">${p.title}</div>
                    <div class="podcast-desc">Podcast</div>
                </div>
            </div>
        `).join('');
    }

    async loadPodcast(index) {
        const podcast = PODCASTS_CONFIG[index];
        this.currentPodcast = podcast;

        // Mark as active
        document.querySelectorAll('.podcast-item').forEach((el, i) => {
            el.classList.toggle('active', i === index);
        });

        this.episodeGrid.innerHTML = '<div class="loader">Chargement des épisodes...</div>';
        document.getElementById('current-podcast-title').innerText = podcast.title;

        // Auto-close sidebar on mobile after selection
        if (window.innerWidth <= 768) {
            this.sidebar.classList.remove('open');
        }

        try {
            // Using a CORS proxy to fetch and parse RSS
            const proxyUrl = `https://api.rss2json.com/v1/api.json?rss_url=${encodeURIComponent(podcast.url)}&count=100`;
            const response = await fetch(proxyUrl);
            const data = await response.json();

            if (data.status === 'ok') {
                this.renderEpisodes(data.items);
            } else {
                throw new Error('Erreur de flux');
            }
        } catch (error) {
            console.error(error);
            this.episodeGrid.innerHTML = '<div class="error">Impossible de charger ce podcast. Problème de flux ou de connexion.</div>';
        }
    }

    renderEpisodes(items) {
        this.episodeGrid.innerHTML = items.map((ep, index) => `
            <div class="episode-card" onclick="app.playEpisode(${JSON.stringify(ep).replace(/"/g, '&quot;')})">
                <div class="episode-header">
                    <span class="episode-date">${new Date(ep.pubDate).toLocaleDateString('fr-FR')}</span>
                    <i data-lucide="play-circle"></i>
                </div>
                <div class="episode-title">${ep.title}</div>
                <div class="episode-summary">${ep.description.replace(/<[^>]*>?/gm, '').substring(0, 150)}...</div>
            </div>
        `).join('');
        lucide.createIcons();
    }

    playEpisode(episode) {
        this.currentEpisode = episode;
        this.audio.src = episode.enclosure.link;
        this.audio.play();
        this.isPlaying = true;
        this.updatePlayerUI();
        this.updateMediaSession();
    }

    togglePlay() {
        if (!this.audio.src) return;
        if (this.isPlaying) {
            this.audio.pause();
        } else {
            this.audio.play();
        }
        this.isPlaying = !this.isPlaying;
        this.updatePlayerUI();
    }

    updatePlayerUI() {
        this.btnPlay.innerHTML = this.isPlaying ? '<i data-lucide="pause"></i>' : '<i data-lucide="play"></i>';
        lucide.createIcons();

        if (this.currentEpisode) {
            this.playerTitle.innerText = this.currentEpisode.title;
            this.playerAuthor.innerText = this.currentPodcast ? this.currentPodcast.title : '';
            this.playerImg.src = this.currentEpisode.thumbnail || (this.currentPodcast ? this.currentPodcast.image_url : '');
            this.playerImg.classList.toggle('playing', this.isPlaying);
        }
    }

    setupEventListeners() {
        this.btnPlay.addEventListener('click', () => this.togglePlay());
        document.getElementById('btn-prev').addEventListener('click', () => { this.audio.currentTime -= 10; });
        document.getElementById('btn-next').addEventListener('click', () => { this.audio.currentTime += 30; });

        this.menuToggle.addEventListener('click', () => {
            this.sidebar.classList.toggle('open');
        });

        this.audio.addEventListener('timeupdate', () => {
            const percent = (this.audio.currentTime / this.audio.duration) * 100;
            this.progressFill.style.width = `${percent}%`;
            this.timeCurrent.innerText = this.formatTime(this.audio.currentTime);
            if (!isNaN(this.audio.duration)) {
                this.timeTotal.innerText = this.formatTime(this.audio.duration);
            }
        });

        this.progressContainer.addEventListener('click', (e) => {
            const rect = this.progressContainer.getBoundingClientRect();
            const pos = (e.clientX - rect.left) / rect.width;
            this.audio.currentTime = pos * this.audio.duration;
        });

        this.audio.addEventListener('ended', () => {
            this.isPlaying = false;
            this.updatePlayerUI();
        });
    }

    formatTime(seconds) {
        const h = Math.floor(seconds / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        const s = Math.floor(seconds % 60);
        return [h, m, s]
            .map(v => v < 10 ? "0" + v : v)
            .filter((v, i) => v !== "00" || i > 0)
            .join(":");
    }

    setupMediaSession() {
        if ('mediaSession' in navigator) {
            navigator.mediaSession.setActionHandler('play', () => this.togglePlay());
            navigator.mediaSession.setActionHandler('pause', () => this.togglePlay());
            navigator.mediaSession.setActionHandler('seekbackward', () => { this.audio.currentTime -= 10; });
            navigator.mediaSession.setActionHandler('seekforward', () => { this.audio.currentTime += 30; });
        }
    }

    updateMediaSession() {
        if ('mediaSession' in navigator && this.currentEpisode) {
            navigator.mediaSession.metadata = new MediaMetadata({
                title: this.currentEpisode.title,
                artist: this.currentPodcast.title,
                artwork: [
                    { src: this.currentEpisode.thumbnail || this.currentPodcast.image_url, sizes: '512x512', type: 'image/jpeg' }
                ]
            });
        }
    }
}

const app = new Podcatcher();
