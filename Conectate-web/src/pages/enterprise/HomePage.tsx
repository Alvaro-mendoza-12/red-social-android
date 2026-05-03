import React, { useState, useEffect, useCallback, useMemo } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { 
  Heart, 
  MessageCircle, 
  Share2, 
  Bookmark, 
  MoreHorizontal,
  Play,
  Volume2,
  VolumeX,
  Maximize2,
  SkipForward,
  SkipBack,
  Repeat,
  Shuffle,
  Pause,
  ChevronLeft,
  ChevronRight,
  TrendingUp,
  Clock,
  Users,
  Music,
  Radio,
  Mic,
  Headphones
} from 'lucide-react'

/**
 * 🔥 ENTERPRISE HOME PAGE - NIVEL TIKTOK/INSTAGRAM REELS
 * Feed ultra-moderno con performance optimizada y animaciones fluidas
 */
interface Post {
  id: string
  author: {
    id: string
    username: string
    avatar: string
    verified: boolean
  }
  content: {
    type: 'image' | 'video' | 'reel' | 'voice' | 'disco'
    url: string
    thumbnail?: string
    duration?: number
    caption: string
    music?: {
      title: string
      artist: string
      cover: string
    }
  }
  stats: {
    likes: number
    comments: number
    shares: number
    views: number
  }
  timestamp: number
  isLiked: boolean
  isBookmarked: boolean
}

interface VoiceRoom {
  id: string
  title: string
  host: {
    id: string
    username: string
    avatar: string
  }
  participants: number
  maxParticipants: number
  isLive: boolean
  category: string
  music?: {
    title: string
    artist: string
  }
}

interface DiscoRoom {
  id: string
  name: string
  dj: {
    id: string
    username: string
    avatar: string
  }
  currentTrack: {
    title: string
    artist: string
    cover: string
    duration: number
    currentTime: number
  }
  listeners: number
  genre: string
  isLive: boolean
}

export const HomePage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'feed' | 'reels' | 'voice' | 'disco'>('feed')
  const [posts, setPosts] = useState<Post[]>([])
  const [voiceRooms, setVoiceRooms] = useState<VoiceRoom[]>([])
  const [discoRooms, setDiscoRooms] = useState<DiscoRoom[]>([])
  const [currentPostIndex, setCurrentPostIndex] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const [isMuted, setIsMuted] = useState(false)
  const [volume, setVolume] = useState(0.7)
  const [isLoading, setIsLoading] = useState(true)
  const [showControls, setShowControls] = useState(false)

  // 🔥 SIMULACIÓN DE DATOS - EN PRODUCCIÓN CONECTAR A API REAL
  useEffect(() => {
    const loadData = async () => {
      setIsLoading(true)
      
      // Simular carga de datos
      await new Promise(resolve => setTimeout(resolve, 1000))
      
      // Posts de ejemplo
      const mockPosts: Post[] = [
        {
          id: '1',
          author: {
            id: 'user1',
            username: 'alex_creator',
            avatar: 'https://picsum.photos/100/100?random=1',
            verified: true
          },
          content: {
            type: 'reel',
            url: 'https://picsum.photos/400/700?random=1',
            thumbnail: 'https://picsum.photos/200/350?random=1',
            duration: 15000,
            caption: 'Amazing sunset vibes 🌅 #sunset #nature #vibes',
            music: {
              title: 'Sunset Dreams',
              artist: 'Chill Vibes',
              cover: 'https://picsum.photos/50/50?random=10'
            }
          },
          stats: {
            likes: 1234,
            comments: 89,
            shares: 45,
            views: 5678
          },
          timestamp: Date.now() - 3600000,
          isLiked: false,
          isBookmarked: false
        },
        {
          id: '2',
          author: {
            id: 'user2',
            username: 'tech_guru',
            avatar: 'https://picsum.photos/100/100?random=2',
            verified: false
          },
          content: {
            type: 'video',
            url: 'https://picsum.photos/400/700?random=2',
            thumbnail: 'https://picsum.photos/200/350?random=2',
            duration: 30000,
            caption: 'Latest tech review is live! 🚀 Check it out #tech #review #gadgets',
            music: {
              title: 'Tech Beat',
              artist: 'Digital Sounds',
              cover: 'https://picsum.photos/50/50?random=11'
            }
          },
          stats: {
            likes: 892,
            comments: 67,
            shares: 23,
            views: 3456
          },
          timestamp: Date.now() - 7200000,
          isLiked: true,
          isBookmarked: false
        }
      ]

      // Voice rooms de ejemplo
      const mockVoiceRooms: VoiceRoom[] = [
        {
          id: 'voice1',
          title: 'Chill Vibes 🎵',
          host: {
            id: 'host1',
            username: 'music_master',
            avatar: 'https://picsum.photos/100/100?random=3'
          },
          participants: 23,
          maxParticipants: 50,
          isLive: true,
          category: 'Music',
          music: {
            title: 'Ambient Sounds',
            artist: 'Nature Vibes'
          }
        },
        {
          id: 'voice2',
          title: 'Tech Talk 💻',
          host: {
            id: 'host2',
            username: 'dev_chatter',
            avatar: 'https://picsum.photos/100/100?random=4'
          },
          participants: 45,
          maxParticipants: 100,
          isLive: true,
          category: 'Technology'
        }
      ]

      // Disco rooms de ejemplo
      const mockDiscoRooms: DiscoRoom[] = [
        {
          id: 'disco1',
          name: 'Electronic Paradise 🎧',
          dj: {
            id: 'dj1',
            username: 'beat_master',
            avatar: 'https://picsum.photos/100/100?random=5'
          },
          currentTrack: {
            title: 'Neon Dreams',
            artist: 'DJ Cosmos',
            cover: 'https://picsum.photos/50/50?random=12',
            duration: 240000,
            currentTime: 120000
          },
          listeners: 156,
          genre: 'Electronic',
          isLive: true
        },
        {
          id: 'disco2',
          name: 'Retro Vibes 🕺',
          dj: {
            id: 'dj2',
            username: 'vintage_beats',
            avatar: 'https://picsum.photos/100/100?random=6'
          },
          currentTrack: {
            title: '80s Flashback',
            artist: 'DJ Retro',
            cover: 'https://picsum.photos/50/50?random=13',
            duration: 180000,
            currentTime: 90000
          },
          listeners: 89,
          genre: 'Retro',
          isLive: true
        }
      ]

      setPosts(mockPosts)
      setVoiceRooms(mockVoiceRooms)
      setDiscoRooms(mockDiscoRooms)
      setIsLoading(false)
    }

    loadData()
  }, [])

  // 🔥 CONTROLES DE VIDEO
  const handlePlayPause = useCallback(() => {
    setIsPlaying(prev => !prev)
  }, [])

  const handleVolumeToggle = useCallback(() => {
    setIsMuted(prev => !prev)
  }, [])

  const handleVolumeChange = useCallback((newVolume: number) => {
    setVolume(newVolume)
    setIsMuted(newVolume === 0)
  }, [])

  const handleNext = useCallback(() => {
    if (currentPostIndex < posts.length - 1) {
      setCurrentPostIndex(prev => prev + 1)
    }
  }, [currentPostIndex, posts.length])

  const handlePrevious = useCallback(() => {
    if (currentPostIndex > 0) {
      setCurrentPostIndex(prev => prev - 1)
    }
  }, [currentPostIndex])

  const handleLike = useCallback((postId: string) => {
    setPosts(prev => prev.map(post => 
      post.id === postId 
        ? { ...post, isLiked: !post.isLiked, stats: { ...post.stats, likes: post.isLiked ? post.stats.likes - 1 : post.stats.likes + 1 } }
        : post
    ))
  }, [])

  const handleBookmark = useCallback((postId: string) => {
    setPosts(prev => prev.map(post => 
      post.id === postId 
        ? { ...post, isBookmarked: !post.isBookmarked }
        : post
    ))
  }, [])

  // 🔥 ANIMATIONS
  const containerVariants = {
    hidden: { opacity: 0 },
    visible: { opacity: 1, transition: { duration: 0.5 } }
  }

  const slideVariants = {
    enter: (direction: number) => ({
      x: direction > 0 ? '100%' : '-100%',
      opacity: 0
    }),
    center: {
      zIndex: 1,
      x: 0,
      opacity: 1
    },
    exit: (direction: number) => ({
      zIndex: 0,
      x: direction < 0 ? '100%' : '-100%',
      opacity: 0
    })
  }

  const [slideDirection, setSlideDirection] = useState(0)

  useEffect(() => {
    setSlideDirection(0)
  }, [currentPostIndex])

  // 🔥 TABS
  const tabs = [
    { id: 'feed', label: 'Feed', icon: TrendingUp },
    { id: 'reels', label: 'Reels', icon: Play },
    { id: 'voice', label: 'Voice', icon: Mic },
    { id: 'disco', label: 'Disco', icon: Music }
  ]

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-center">
          <div className="w-16 h-16 border-4 border-pink-500 border-t-transparent rounded-full animate-spin mx-auto mb-4"></div>
          <p className="text-gray-600 dark:text-gray-400">Cargando contenido...</p>
        </div>
      </div>
    )
  }

  return (
    <motion.div
      variants={containerVariants}
      initial="hidden"
      animate="visible"
      className="h-full flex flex-col"
    >
      {/* 🔥 TABS */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200 dark:border-gray-700">
        <div className="flex space-x-1">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as any)}
              className={`
                px-4 py-2 rounded-full font-medium transition-all duration-200
                ${activeTab === tab.id 
                  ? 'bg-gradient-to-r from-pink-500 to-purple-600 text-white' 
                  : 'text-gray-600 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-800'
                }
              `}
            >
              <tab.icon className="w-4 h-4 inline mr-2" />
              {tab.label}
            </button>
          ))}
        </div>
        
        <div className="flex items-center space-x-2">
          <button className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors">
            <Clock className="w-4 h-4" />
          </button>
          <button className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors">
            <Users className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* 🔥 CONTENT */}
      <div className="flex-1 relative overflow-hidden">
        
        {/* 🔥 FEED TAB */}
        {activeTab === 'feed' && (
          <div className="h-full overflow-y-auto">
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4 p-4">
              {posts.map((post, index) => (
                <motion.div
                  key={post.id}
                  initial={{ opacity: 0, scale: 0.9 }}
                  animate={{ opacity: 1, scale: 1 }}
                  transition={{ delay: index * 0.05 }}
                  className="bg-white dark:bg-gray-800 rounded-2xl shadow-lg overflow-hidden hover:shadow-xl transition-shadow"
                >
                  {/* Post Content */}
                  <div className="relative aspect-[9/16] bg-gray-100 dark:bg-gray-700">
                    <img 
                      src={post.content.thumbnail || post.content.url} 
                      alt={post.content.caption}
                      className="w-full h-full object-cover"
                    />
                    
                    {/* Play Button for Videos */}
                    {(post.content.type === 'video' || post.content.type === 'reel') && (
                      <div className="absolute inset-0 flex items-center justify-center bg-black/30">
                        <button className="w-16 h-16 bg-white/90 rounded-full flex items-center justify-center hover:bg-white transition-colors">
                          <Play className="w-8 h-8 text-gray-900 ml-1" />
                        </button>
                      </div>
                    )}
                    
                    {/* Duration */}
                    {post.content.duration && (
                      <div className="absolute bottom-2 right-2 bg-black/70 text-white text-xs px-2 py-1 rounded">
                        {Math.floor(post.content.duration / 1000)}s
                      </div>
                    )}
                  </div>
                  
                  {/* Post Info */}
                  <div className="p-4">
                    {/* Author */}
                    <div className="flex items-center justify-between mb-3">
                      <div className="flex items-center space-x-2">
                        <img 
                          src={post.author.avatar} 
                          alt={post.author.username}
                          className="w-8 h-8 rounded-full"
                        />
                        <div>
                          <p className="font-medium text-sm">{post.author.username}</p>
                          {post.author.verified && (
                            <div className="w-3 h-3 bg-blue-500 rounded-full"></div>
                          )}
                        </div>
                      </div>
                      
                      <button className="p-1 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors">
                        <MoreHorizontal className="w-4 h-4" />
                      </button>
                    </div>
                    
                    {/* Caption */}
                    <p className="text-sm text-gray-700 dark:text-gray-300 mb-3 line-clamp-2">
                      {post.content.caption}
                    </p>
                    
                    {/* Music */}
                    {post.content.music && (
                      <div className="flex items-center space-x-2 mb-3 p-2 bg-gray-100 dark:bg-gray-700 rounded-lg">
                        <img 
                          src={post.content.music.cover} 
                          alt={post.content.music.title}
                          className="w-8 h-8 rounded"
                        />
                        <div className="flex-1">
                          <p className="text-xs font-medium truncate">{post.content.music.title}</p>
                          <p className="text-xs text-gray-500 truncate">{post.content.music.artist}</p>
                        </div>
                        <Music className="w-4 h-4 text-gray-500" />
                      </div>
                    )}
                    
                    {/* Actions */}
                    <div className="flex items-center justify-between">
                      <div className="flex items-center space-x-4">
                        <button 
                          onClick={() => handleLike(post.id)}
                          className={`flex items-center space-x-1 transition-colors ${
                            post.isLiked ? 'text-red-500' : 'text-gray-600 dark:text-gray-400 hover:text-red-500'
                          }`}
                        >
                          <Heart className={`w-5 h-5 ${post.isLiked ? 'fill-current' : ''}`} />
                          <span className="text-sm">{formatNumber(post.stats.likes)}</span>
                        </button>
                        
                        <button className="flex items-center space-x-1 text-gray-600 dark:text-gray-400 hover:text-blue-500 transition-colors">
                          <MessageCircle className="w-5 h-5" />
                          <span className="text-sm">{formatNumber(post.stats.comments)}</span>
                        </button>
                        
                        <button className="flex items-center space-x-1 text-gray-600 dark:text-gray-400 hover:text-green-500 transition-colors">
                          <Share2 className="w-5 h-5" />
                          <span className="text-sm">{formatNumber(post.stats.shares)}</span>
                        </button>
                      </div>
                      
                      <button 
                        onClick={() => handleBookmark(post.id)}
                        className={`transition-colors ${
                          post.isBookmarked ? 'text-yellow-500' : 'text-gray-600 dark:text-gray-400 hover:text-yellow-500'
                        }`}
                      >
                        <Bookmark className={`w-5 h-5 ${post.isBookmarked ? 'fill-current' : ''}`} />
                      </button>
                    </div>
                  </div>
                </motion.div>
              ))}
            </div>
          </div>
        )}

        {/* 🔥 REELS TAB - FULL SCREEN EXPERIENCE */}
        {activeTab === 'reels' && posts.length > 0 && (
          <div className="h-full relative bg-black">
            <AnimatePresence initial={false} custom={(direction) => setSlideDirection(direction)}>
              <motion.div
                key={currentPostIndex}
                custom={slideDirection}
                variants={slideVariants}
                initial="enter"
                animate="center"
                exit="exit"
                transition={{ x: { type: "spring", stiffness: 300, damping: 30 }, opacity: { duration: 0.2 } }}
                className="h-full relative"
              >
                <div className="h-full relative">
                  {/* Video Background */}
                  <img 
                    src={posts[currentPostIndex].content.url}
                    alt={posts[currentPostIndex].content.caption}
                    className="w-full h-full object-cover"
                  />
                  
                  {/* Controls Overlay */}
                  <div 
                    className="absolute inset-0 bg-gradient-to-b from-black/50 via-transparent to-black/50"
                    onMouseEnter={() => setShowControls(true)}
                    onMouseLeave={() => setShowControls(false)}
                  >
                    {/* Top Controls */}
                    <div className="absolute top-4 left-4 right-4 flex items-center justify-between">
                      <button className="p-2 bg-black/50 rounded-full text-white">
                        <ChevronLeft className="w-5 h-5" />
                      </button>
                      
                      <div className="flex items-center space-x-2">
                        <button className="p-2 bg-black/50 rounded-full text-white">
                          <Volume2 className="w-5 h-5" />
                        </button>
                        <button className="p-2 bg-black/50 rounded-full text-white">
                          <Maximize2 className="w-5 h-5" />
                        </button>
                      </div>
                    </div>
                    
                    {/* Bottom Controls */}
                    <div className="absolute bottom-0 left-0 right-0 p-4">
                      <div className="flex items-end justify-between">
                        {/* Left Side - Content */}
                        <div className="flex-1 mr-4">
                          {/* Author */}
                          <div className="flex items-center space-x-3 mb-4">
                            <img 
                              src={posts[currentPostIndex].author.avatar}
                              alt={posts[currentPostIndex].author.username}
                              className="w-10 h-10 rounded-full border-2 border-white"
                            />
                            <div>
                              <p className="text-white font-medium">{posts[currentPostIndex].author.username}</p>
                              {posts[currentPostIndex].author.verified && (
                                <div className="w-4 h-4 bg-blue-500 rounded-full"></div>
                              )}
                            </div>
                            <button className="ml-4 px-3 py-1 bg-red-500 text-white text-sm rounded-full font-medium">
                              Seguir
                            </button>
                          </div>
                          
                          {/* Caption */}
                          <p className="text-white mb-4 line-clamp-2">
                            {posts[currentPostIndex].content.caption}
                          </p>
                          
                          {/* Music */}
                          {posts[currentPostIndex].content.music && (
                            <div className="flex items-center space-x-2 p-2 bg-black/30 rounded-lg backdrop-blur-sm">
                              <Music className="w-4 h-4 text-white" />
                              <div className="flex-1">
                                <p className="text-white text-sm font-medium truncate">{posts[currentPostIndex].content.music.title}</p>
                                <p className="text-white/70 text-xs truncate">{posts[currentPostIndex].content.music.artist}</p>
                              </div>
                            </div>
                          )}
                        </div>
                        
                        {/* Right Side - Actions */}
                        <div className="flex flex-col items-center space-y-4">
                          <button 
                            onClick={() => handleLike(posts[currentPostIndex].id)}
                            className={`p-3 rounded-full ${
                              posts[currentPostIndex].isLiked ? 'bg-red-500' : 'bg-white/20'
                            }`}
                          >
                            <Heart className={`w-6 h-6 text-white ${posts[currentPostIndex].isLiked ? 'fill-current' : ''}`} />
                          </button>
                          <span className="text-white text-sm">{formatNumber(posts[currentPostIndex].stats.likes)}</span>
                          
                          <button className="p-3 bg-white/20 rounded-full">
                            <MessageCircle className="w-6 h-6 text-white" />
                          </button>
                          <span className="text-white text-sm">{formatNumber(posts[currentPostIndex].stats.comments)}</span>
                          
                          <button className="p-3 bg-white/20 rounded-full">
                            <Share2 className="w-6 h-6 text-white" />
                          </button>
                          <span className="text-white text-sm">{formatNumber(posts[currentPostIndex].stats.shares)}</span>
                          
                          <button 
                            onClick={() => handleBookmark(posts[currentPostIndex].id)}
                            className={`p-3 rounded-full ${
                              posts[currentPostIndex].isBookmarked ? 'bg-yellow-500' : 'bg-white/20'
                            }`}
                          >
                            <Bookmark className={`w-6 h-6 text-white ${posts[currentPostIndex].isBookmarked ? 'fill-current' : ''}`} />
                          </button>
                          
                          {/* Progress Bar */}
                          <div className="w-12 h-16 flex items-center">
                            <div className="w-full h-1 bg-white/30 rounded-full overflow-hidden">
                              <div className="h-full bg-white rounded-full" style={{ width: '60%' }}></div>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                    
                    {/* Center Controls */}
                    {showControls && (
                      <div className="absolute inset-0 flex items-center justify-center">
                        <div className="flex items-center space-x-8">
                          <button className="p-4 bg-white/20 rounded-full">
                            <SkipBack className="w-8 h-8 text-white" />
                          </button>
                          
                          <button 
                            onClick={handlePlayPause}
                            className="p-6 bg-white/30 rounded-full hover:bg-white/40 transition-colors"
                          >
                            {isPlaying ? (
                              <Pause className="w-12 h-12 text-white" />
                            ) : (
                              <Play className="w-12 h-12 text-white ml-1" />
                            )}
                          </button>
                          
                          <button className="p-4 bg-white/20 rounded-full">
                            <SkipForward className="w-8 h-8 text-white" />
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              </motion.div>
            </AnimatePresence>
            
            {/* Navigation Arrows */}
            <button 
              onClick={handlePrevious}
              className="absolute left-4 top-1/2 transform -translate-y-1/2 p-2 bg-black/50 rounded-full text-white hover:bg-black/70 transition-colors"
              disabled={currentPostIndex === 0}
            >
              <ChevronLeft className="w-6 h-6" />
            </button>
            
            <button 
              onClick={handleNext}
              className="absolute right-4 top-1/2 transform -translate-y-1/2 p-2 bg-black/50 rounded-full text-white hover:bg-black/70 transition-colors"
              disabled={currentPostIndex === posts.length - 1}
            >
              <ChevronRight className="w-6 h-6" />
            </button>
          </div>
        )}

        {/* 🔥 VOICE ROOMS TAB */}
        {activeTab === 'voice' && (
          <div className="h-full overflow-y-auto p-4">
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {voiceRooms.map((room, index) => (
                <motion.div
                  key={room.id}
                  initial={{ opacity: 0, y: 20 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: index * 0.05 }}
                  className="bg-gradient-to-br from-purple-600 to-pink-600 rounded-2xl p-6 text-white shadow-lg hover:shadow-xl transition-shadow"
                >
                  {/* Header */}
                  <div className="flex items-center justify-between mb-4">
                    <div className="flex items-center space-x-3">
                      <div className="relative">
                        <img 
                          src={room.host.avatar}
                          alt={room.host.username}
                          className="w-12 h-12 rounded-full border-2 border-white"
                        />
                        {room.isLive && (
                          <div className="absolute -bottom-1 -right-1 w-4 h-4 bg-red-500 rounded-full border-2 border-white animate-pulse"></div>
                        )}
                      </div>
                      <div>
                        <h3 className="font-bold">{room.title}</h3>
                        <p className="text-sm opacity-90">Host: {room.host.username}</p>
                      </div>
                    </div>
                    
                    <div className="flex items-center space-x-2">
                      <Mic className="w-4 h-4" />
                      <Headphones className="w-4 h-4" />
                    </div>
                  </div>
                  
                  {/* Participants */}
                  <div className="mb-4">
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-sm opacity-90">Participantes</span>
                      <span className="text-sm font-bold">
                        {room.participants}/{room.maxParticipants}
                      </span>
                    </div>
                    <div className="w-full bg-white/20 rounded-full h-2">
                      <div 
                        className="bg-white rounded-full h-2 transition-all duration-300"
                        style={{ width: `${(room.participants / room.maxParticipants) * 100}%` }}
                      ></div>
                    </div>
                  </div>
                  
                  {/* Category */}
                  <div className="mb-4">
                    <span className="px-3 py-1 bg-white/20 rounded-full text-sm">
                      {room.category}
                    </span>
                  </div>
                  
                  {/* Music */}
                  {room.music && (
                    <div className="mb-4 p-3 bg-white/10 rounded-lg">
                      <div className="flex items-center space-x-2">
                        <Music className="w-4 h-4" />
                        <div className="flex-1">
                          <p className="text-sm font-medium truncate">{room.music.title}</p>
                          <p className="text-xs opacity-70 truncate">{room.music.artist}</p>
                        </div>
                      </div>
                    </div>
                  )}
                  
                  {/* Join Button */}
                  <button className="w-full py-3 bg-white text-purple-600 rounded-lg font-bold hover:bg-gray-100 transition-colors">
                    {room.isLive ? 'Unirse Ahora' : 'Ver Detalles'}
                  </button>
                </motion.div>
              ))}
            </div>
          </div>
        )}

        {/* 🔥 DISCO ROOMS TAB */}
        {activeTab === 'disco' && (
          <div className="h-full overflow-y-auto p-4">
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {discoRooms.map((room, index) => (
                <motion.div
                  key={room.id}
                  initial={{ opacity: 0, y: 20 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: index * 0.05 }}
                  className="bg-gradient-to-br from-pink-600 to-purple-600 rounded-2xl p-6 text-white shadow-lg hover:shadow-xl transition-shadow"
                >
                  {/* Header */}
                  <div className="flex items-center justify-between mb-4">
                    <div className="flex items-center space-x-3">
                      <div className="relative">
                        <img 
                          src={room.dj.avatar}
                          alt={room.dj.username}
                          className="w-12 h-12 rounded-full border-2 border-white"
                        />
                        {room.isLive && (
                          <div className="absolute -bottom-1 -right-1 w-4 h-4 bg-red-500 rounded-full border-2 border-white animate-pulse"></div>
                        )}
                      </div>
                      <div>
                        <h3 className="font-bold">{room.name}</h3>
                        <p className="text-sm opacity-90">DJ: {room.dj.username}</p>
                      </div>
                    </div>
                    
                    <div className="flex items-center space-x-2">
                      <Radio className="w-4 h-4" />
                      <Music className="w-4 h-4" />
                    </div>
                  </div>
                  
                  {/* Current Track */}
                  <div className="mb-4 p-3 bg-white/10 rounded-lg">
                    <div className="flex items-center space-x-3">
                      <img 
                        src={room.currentTrack.cover}
                        alt={room.currentTrack.title}
                        className="w-12 h-12 rounded"
                      />
                      <div className="flex-1">
                        <p className="text-sm font-medium truncate">{room.currentTrack.title}</p>
                        <p className="text-xs opacity-70 truncate">{room.currentTrack.artist}</p>
                        <div className="mt-1 w-full bg-white/20 rounded-full h-1">
                          <div 
                            className="bg-white rounded-full h-1 transition-all duration-300"
                            style={{ width: `${(room.currentTrack.currentTime / room.currentTrack.duration) * 100}%` }}
                          ></div>
                        </div>
                      </div>
                    </div>
                  </div>
                  
                  {/* Listeners */}
                  <div className="mb-4">
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-sm opacity-90">Escuchando</span>
                      <span className="text-sm font-bold">{room.listeners}</span>
                    </div>
                  </div>
                  
                  {/* Genre */}
                  <div className="mb-4">
                    <span className="px-3 py-1 bg-white/20 rounded-full text-sm">
                      {room.genre}
                    </span>
                  </div>
                  
                  {/* Join Button */}
                  <button className="w-full py-3 bg-white text-pink-600 rounded-lg font-bold hover:bg-gray-100 transition-colors">
                    {room.isLive ? 'Entrar a la Disco' : 'Ver Detalles'}
                  </button>
                </motion.div>
              ))}
            </div>
          </div>
        )}
      </div>
    </motion.div>
  )
}

// 🔥 UTILIDADES
const formatNumber = (num: number): string => {
  if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`
  if (num >= 1000) return `${(num / 1000).toFixed(1)}K`
  return num.toString()
}
